package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.item.RouteCardItem;
import com.example.railwayvehicleaddon.operation.OperationState;
import com.example.railwayvehicleaddon.operation.Timetable;
import com.example.railwayvehicleaddon.screen.OperationControllerScreenHandler;
import com.example.tudursvehiclemod.ChunkForceTracker;
import com.example.tudursvehiclemod.item.DroneControlStickItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 運行管理装置のブロックエンティティ。組(制御スティック・経路アイテム)ごとに、スティックに登録した車両を含む編成へ
 * 経路アイテムの運行表を割り当て、ATOで運転させる。
 *
 * <p>チャンクの読み込み(前提MODのドローン制御ブロックと同じ方式。前提MODの {@link ChunkForceTracker} を使う):
 * <ul>
 *   <li>この装置のチャンク: 置いている間ずっと</li>
 *   <li>割り当てた編成: 編成の在線範囲の周り(3×3)と、前方の先読みの経路(線路の両側8ブロックまで)。
 *       編成の全車両について読み込むので、長い編成でも途中の車両が止まらない。編成と一緒に動く</li>
 *   <li>編成が見つからない(読み込まれていない)とき: 最後に見えた編成の各車両のチャンクを、一定時間ごとに
 *       一時的に読み込んで探す(サーバーの再起動後など)</li>
 * </ul>
 */
public class OperationControllerBlockEntity extends BlockEntity implements NamedScreenHandlerFactory {
	public static final int MAX_ROWS = 999;
	/** 組を処理する間隔(tick) */
	private static final int PROCESS_INTERVAL = 10;
	/** 見つからない編成を探す周期と、そのうち読み込んでおく長さ(tick) */
	private static final int SEARCH_PERIOD = 200;
	private static final int SEARCH_WINDOW = 100;

	/** 組の状態(画面の表示用) */
	public static final int STATUS_EMPTY = 0;
	public static final int STATUS_NO_STICK = 1;
	public static final int STATUS_NO_CARD = 2;
	public static final int STATUS_SEARCHING = 3;
	public static final int STATUS_NOT_RAIL = 4;
	public static final int STATUS_DUPLICATE = 5;
	public static final int STATUS_RUNNING = 6;
	public static final int STATUS_MANUAL = 7;

	private DefaultedList<ItemStack> items = DefaultedList.ofSize(2, ItemStack.EMPTY);
	private int rows = 1;
	private int page;
	private final List<Row> rowStates = new ArrayList<>();
	private boolean ownChunkRequested;
	private final RowsInventory inventory = new RowsInventory();

	/** 組ごとの、保存しない状態(と、保存する最後に見えた場所) */
	private static final class Row {
		Set<ChunkPos> forced = Set.of();
		List<ChunkPos> lastKnown = List.of();
		List<ChunkPos> searchForced = List.of();
		int searchTimer;
		UUID vehicle;
		int status;
		int display;
		int stopIndex;
	}

	public OperationControllerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.OPERATION_CONTROLLER_ENTITY, pos, state);
		ensureRowStates();
	}

	private void ensureRowStates() {
		while (this.rowStates.size() < this.rows) {
			this.rowStates.add(new Row());
		}
		while (this.rowStates.size() > this.rows) {
			this.rowStates.remove(this.rowStates.size() - 1);
		}
	}

	public int rows() {
		return this.rows;
	}

	public int page() {
		return this.page;
	}

	public int pageCount() {
		return Math.max(1, (this.rows + OperationControllerScreenHandler.PAGE_ROWS - 1) / OperationControllerScreenHandler.PAGE_ROWS);
	}

	public Inventory inventory() {
		return this.inventory;
	}

	public int status(int row) {
		return row >= 0 && row < this.rowStates.size() ? this.rowStates.get(row).status : STATUS_EMPTY;
	}

	public int display(int row) {
		return row >= 0 && row < this.rowStates.size() ? this.rowStates.get(row).display : 0;
	}

	public int stopIndex(int row) {
		return row >= 0 && row < this.rowStates.size() ? this.rowStates.get(row).stopIndex : 0;
	}

	public ItemStack stick(int row) {
		return row >= 0 && row < this.rows ? this.items.get(row * 2) : ItemStack.EMPTY;
	}

	public ItemStack card(int row) {
		return row >= 0 && row < this.rows ? this.items.get(row * 2 + 1) : ItemStack.EMPTY;
	}

	/** 組rowが、今も車両vehicleを割り当てているか(スティックの登録先がvehicleで、経路アイテムに駅がある)。 */
	public boolean assigns(int row, UUID vehicle) {
		if (row < 0 || row >= this.rows) {
			return false;
		}
		UUID id = registeredVehicle(stick(row));
		ItemStack card = card(row);
		return id != null && id.equals(vehicle) && card.getItem() instanceof RouteCardItem && !RouteCardItem.get(card).isEmpty();
	}

	private static UUID registeredVehicle(ItemStack stick) {
		return stick.getItem() instanceof DroneControlStickItem ? DroneControlStickItem.getRegisteredVehicleId(stick) : null;
	}

	// ------------------------------------------------------------------ 画面の操作

	/** 画面のボタン。0/1=前・次のページ、2/3=組を1つ減らす・増やす、4/5=10減らす・増やす、10+i=組iを最初の駅から、20+i=組iのATO入切(iはページの中の番号)。 */
	public boolean handleButton(int id) {
		int pageRows = OperationControllerScreenHandler.PAGE_ROWS;
		switch (id) {
			case 0 -> this.page = Math.max(0, this.page - 1);
			case 1 -> this.page = Math.min(pageCount() - 1, this.page + 1);
			case 2 -> setRows(this.rows - 1);
			case 3 -> setRows(this.rows + 1);
			case 4 -> setRows(this.rows - 10);
			case 5 -> setRows(this.rows + 10);
			default -> {
				int row = this.page * pageRows + (id % 10);
				if (id >= 10 && id < 10 + pageRows) {
					RailVehicleEntity car = assignedCar(row);
					if (car != null) {
						car.restartTimetable();
						car.atoState().enabled = true;
					}
				} else if (id >= 20 && id < 20 + pageRows) {
					RailVehicleEntity car = assignedCar(row);
					if (car != null) {
						car.atoState().enabled = !car.atoState().enabled;
						car.atoState().dir = 0;
					}
				} else {
					return false;
				}
			}
		}
		markDirty();
		return true;
	}

	private RailVehicleEntity assignedCar(int row) {
		if (!(this.world instanceof ServerWorld serverWorld) || row < 0 || row >= this.rows) {
			return null;
		}
		UUID id = registeredVehicle(stick(row));
		if (id != null && serverWorld.getEntity(id) instanceof RailVehicleEntity car && this.pos.equals(car.managedBy()) && car.managedRow() == row) {
			return car;
		}
		return null;
	}

	/** 組の数を変える。減らした組のスティック・経路アイテムは、装置の位置に落とす。 */
	private void setRows(int count) {
		int next = Math.max(1, Math.min(MAX_ROWS, count));
		if (next == this.rows) {
			return;
		}
		DefaultedList<ItemStack> resized = DefaultedList.ofSize(next * 2, ItemStack.EMPTY);
		for (int i = 0; i < this.items.size(); i++) {
			if (i < resized.size()) {
				resized.set(i, this.items.get(i));
			} else if (!this.items.get(i).isEmpty() && this.world != null) {
				ItemScatterer.spawn(this.world, this.pos.getX() + 0.5, this.pos.getY() + 1.0, this.pos.getZ() + 0.5, this.items.get(i));
			}
		}
		if (this.world instanceof ServerWorld serverWorld) {
			for (int r = next; r < this.rows; r++) {
				releaseRow(serverWorld, r);
			}
		}
		this.items = resized;
		this.rows = next;
		ensureRowStates();
		this.page = Math.min(this.page, pageCount() - 1);
	}

	// ------------------------------------------------------------------ 処理

	public static void tick(World world, BlockPos pos, BlockState state, OperationControllerBlockEntity entity) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}
		if (!entity.ownChunkRequested) {
			entity.updateOwnChunk(true);
		}
		long now = serverWorld.getTime();
		for (int r = 0; r < entity.rows; r++) {
			if ((now + r) % PROCESS_INTERVAL == 0) {
				entity.processRow(serverWorld, r, now);
			}
		}
	}

	private void processRow(ServerWorld world, int r, long now) {
		Row row = this.rowStates.get(r);
		ItemStack stick = stick(r);
		ItemStack card = card(r);
		UUID id = registeredVehicle(stick);
		Timetable table = card.getItem() instanceof RouteCardItem ? RouteCardItem.get(card) : null;
		if (id == null || table == null || table.isEmpty()) {
			row.status = stick.isEmpty() && card.isEmpty() ? STATUS_EMPTY : id == null ? STATUS_NO_STICK : STATUS_NO_CARD;
			releaseAssignment(world, r);
			return;
		}
		if (row.vehicle != null && !row.vehicle.equals(id)) {
			// スティックを差し替えた: 前の車両の割り当てを外す
			releaseAssignment(world, r);
		}
		row.vehicle = id;
		Entity entity = world.getEntity(id);
		if (entity == null) {
			row.status = STATUS_SEARCHING;
			releaseChunks(world, r);
			search(world, r);
			return;
		}
		releaseSearch(world, r);
		if (!(entity instanceof RailVehicleEntity car)) {
			row.status = STATUS_NOT_RAIL;
			return;
		}
		if (car.isManaged() && !(this.pos.equals(car.managedBy()) && car.managedRow() == r) && otherStillAssigns(world, car)) {
			// ほかの運行管理装置(またはこの装置のほかの組)がすでに割り当てている: 先に割り当てた方を優先する
			row.status = STATUS_DUPLICATE;
			releaseChunks(world, r);
			return;
		}
		List<RailVehicleEntity> consistCars = car.consistCars();
		for (RailVehicleEntity other : consistCars) {
			if (other != car && other.isManaged() && otherStillAssigns(world, other)) {
				// 同じ編成のほかの車両を、ほかの組・装置が割り当てている: 先に割り当てた方を優先する
				row.status = STATUS_DUPLICATE;
				releaseChunks(world, r);
				return;
			}
		}
		int hash = table.hashCode();
		if (!this.pos.equals(car.managedBy()) || car.managedRow() != r || car.managedHash() != hash || car.getTimetable() == null) {
			for (RailVehicleEntity other : consistCars) {
				if (other != car && other.getTimetable() != null) {
					other.clearTimetable();
				}
			}
			car.assignTimetable(table, this.pos, r, hash);
		}

		// チャンクの読み込み(編成の全車両の周りと、先読みの経路)
		OperationState state = OperationState.get(world);
		OperationState.Consist consist = state.consistOf(car.getUuid(), now);
		Set<ChunkPos> want = new HashSet<>(consist != null ? state.loadChunks(consist.key()) : Set.of());
		List<ChunkPos> seen = new ArrayList<>();
		for (RailVehicleEntity member : consistCars) {
			ChunkPos c = member.getChunkPos();
			seen.add(c);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					want.add(new ChunkPos(c.x + dx, c.z + dz));
				}
			}
		}
		Object requester = rowRequester(r);
		for (ChunkPos c : row.forced) {
			if (!want.contains(c)) {
				ChunkForceTracker.release(world, c, requester);
			}
		}
		for (ChunkPos c : want) {
			if (!row.forced.contains(c)) {
				ChunkForceTracker.request(world, c, requester);
			}
		}
		row.forced = want;
		if (!seen.equals(row.lastKnown)) {
			row.lastKnown = seen;
			markDirty();
		}
		row.status = car.atoState().enabled ? STATUS_RUNNING : STATUS_MANUAL;
		row.display = car.atoState().display.ordinal();
		row.stopIndex = car.stopIndex();
	}

	/** 車両carを割り当てている装置が、今もcarを割り当てているか。 */
	private static boolean otherStillAssigns(ServerWorld world, RailVehicleEntity car) {
		BlockPos other = car.managedBy();
		if (other == null || !world.isChunkLoaded(other)) {
			return other != null;
		}
		return world.getBlockEntity(other) instanceof OperationControllerBlockEntity controller
				&& controller.assigns(car.managedRow(), car.getUuid());
	}

	/** 見つからない編成を、最後に見えた場所を一時的に読み込んで探す。 */
	private void search(ServerWorld world, int r) {
		Row row = this.rowStates.get(r);
		if (row.lastKnown.isEmpty()) {
			return;
		}
		row.searchTimer -= PROCESS_INTERVAL;
		if (row.searchTimer <= 0) {
			row.searchTimer = SEARCH_PERIOD;
			Object requester = searchRequester(r);
			for (ChunkPos c : row.lastKnown) {
				ChunkForceTracker.requestWithoutLoading(world, c, requester);
			}
			row.searchForced = row.lastKnown;
		} else if (row.searchTimer <= SEARCH_PERIOD - SEARCH_WINDOW) {
			releaseSearch(world, r);
		}
	}

	private void releaseSearch(ServerWorld world, int r) {
		Row row = this.rowStates.get(r);
		if (row.searchForced.isEmpty()) {
			return;
		}
		Object requester = searchRequester(r);
		for (ChunkPos c : row.searchForced) {
			ChunkForceTracker.release(world, c, requester);
		}
		row.searchForced = List.of();
	}

	private void releaseChunks(ServerWorld world, int r) {
		Row row = this.rowStates.get(r);
		Object requester = rowRequester(r);
		for (ChunkPos c : row.forced) {
			ChunkForceTracker.release(world, c, requester);
		}
		row.forced = Set.of();
	}

	/** 組rの割り当て(読み込まれていれば車両の運行表)とチャンクの読み込みを外す。 */
	private void releaseAssignment(ServerWorld world, int r) {
		Row row = this.rowStates.get(r);
		if (row.vehicle != null && world.getEntity(row.vehicle) instanceof RailVehicleEntity car
				&& this.pos.equals(car.managedBy()) && car.managedRow() == r) {
			car.clearTimetable();
		}
		row.vehicle = null;
		row.display = 0;
		row.stopIndex = 0;
		releaseChunks(world, r);
		releaseSearch(world, r);
		if (!row.lastKnown.isEmpty()) {
			row.lastKnown = List.of();
			markDirty();
		}
	}

	private void releaseRow(ServerWorld world, int r) {
		if (r < this.rowStates.size()) {
			releaseAssignment(world, r);
		}
	}

	private ChunkForceTracker.Purpose rowRequester(int r) {
		return new ChunkForceTracker.Purpose(this, "row" + r);
	}

	private ChunkForceTracker.Purpose searchRequester(int r) {
		return new ChunkForceTracker.Purpose(this, "search" + r);
	}

	/** この装置のチャンクを読み込み続ける(BLOCK_ENTITY_LOAD・最初のtickで呼ぶ)。 */
	public void updateOwnChunk(boolean forced) {
		if (this.world instanceof ServerWorld serverWorld) {
			ChunkPos own = new ChunkPos(this.pos);
			if (forced) {
				ChunkForceTracker.requestWithoutLoading(serverWorld, own, this);
				this.ownChunkRequested = true;
			} else {
				ChunkForceTracker.release(serverWorld, own, this);
				this.ownChunkRequested = false;
			}
		}
	}

	/** 壊された: 割り当てを外し、スロットの中身を落とし、読み込んでいたチャンクをすべて外す。 */
	@Override
	public void onBlockReplaced(BlockPos pos, BlockState oldState) {
		if (this.world instanceof ServerWorld serverWorld) {
			for (int r = 0; r < this.rows; r++) {
				releaseAssignment(serverWorld, r);
			}
			ItemScatterer.spawn(serverWorld, pos, this.items);
			this.items.clear();
			ChunkForceTracker.releaseAllOwnedBy(serverWorld, this);
		}
		super.onBlockReplaced(pos, oldState);
	}

	/** チャンクの読み込みが外れた(サーバーの停止など): 読み込みの記録だけ忘れる(強制読み込みはワールドと一緒に保存される)。 */
	@Override
	public void markRemoved() {
		super.markRemoved();
		if (this.world instanceof ServerWorld serverWorld) {
			ChunkForceTracker.forgetAllOwnedBy(serverWorld, this);
		}
	}

	// ------------------------------------------------------------------ 保存

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.rows = Math.max(1, Math.min(MAX_ROWS, view.getInt("Rows", 1)));
		this.items = DefaultedList.ofSize(this.rows * 2, ItemStack.EMPTY);
		Inventories.readData(view, this.items);
		this.page = Math.max(0, Math.min(pageCount() - 1, view.getInt("Page", 0)));
		this.rowStates.clear();
		ensureRowStates();
		int[] known = view.getOptionalIntArray("LastKnown").orElse(new int[0]);
		int i = 0;
		for (int r = 0; r < this.rows && i < known.length; r++) {
			int count = known[i++];
			List<ChunkPos> list = new ArrayList<>(Math.max(0, count));
			for (int k = 0; k < count && i + 1 < known.length; k++) {
				list.add(new ChunkPos(known[i], known[i + 1]));
				i += 2;
			}
			this.rowStates.get(r).lastKnown = list;
		}
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putInt("Rows", this.rows);
		view.putInt("Page", this.page);
		Inventories.writeData(view, this.items, true);
		List<Integer> known = new ArrayList<>();
		for (Row row : this.rowStates) {
			known.add(row.lastKnown.size());
			for (ChunkPos c : row.lastKnown) {
				known.add(c.x);
				known.add(c.z);
			}
		}
		view.putIntArray("LastKnown", known.stream().mapToInt(Integer::intValue).toArray());
	}

	// ------------------------------------------------------------------ 画面

	@Override
	public Text getDisplayName() {
		return Text.translatable("container.railwayvehicleaddon.operation_controller");
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new OperationControllerScreenHandler(syncId, playerInventory, this);
	}

	/** 画面へ同期する値(組の数・ページ・ページ内の各組の状態・ATOの状態・次の駅の番号)。 */
	public PropertyDelegate properties() {
		return new PropertyDelegate() {
			@Override
			public int get(int index) {
				if (index == 0) {
					return OperationControllerBlockEntity.this.rows;
				}
				if (index == 1) {
					return OperationControllerBlockEntity.this.page;
				}
				int k = index - 2;
				int row = OperationControllerBlockEntity.this.page * OperationControllerScreenHandler.PAGE_ROWS + k / 3;
				return switch (k % 3) {
					case 0 -> status(row);
					case 1 -> display(row);
					default -> stopIndex(row);
				};
			}

			@Override
			public void set(int index, int value) {
			}

			@Override
			public int size() {
				return OperationControllerScreenHandler.PROPERTY_COUNT;
			}
		};
	}

	/** スロット全体(組r: 2r=スティック、2r+1=経路アイテム)。 */
	public final class RowsInventory implements Inventory {
		@Override
		public int size() {
			return OperationControllerBlockEntity.this.items.size();
		}

		@Override
		public boolean isEmpty() {
			for (ItemStack stack : OperationControllerBlockEntity.this.items) {
				if (!stack.isEmpty()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public ItemStack getStack(int slot) {
			return slot >= 0 && slot < size() ? OperationControllerBlockEntity.this.items.get(slot) : ItemStack.EMPTY;
		}

		@Override
		public ItemStack removeStack(int slot, int amount) {
			if (slot < 0 || slot >= size()) {
				return ItemStack.EMPTY;
			}
			ItemStack result = Inventories.splitStack(OperationControllerBlockEntity.this.items, slot, amount);
			if (!result.isEmpty()) {
				markDirty();
			}
			return result;
		}

		@Override
		public ItemStack removeStack(int slot) {
			if (slot < 0 || slot >= size()) {
				return ItemStack.EMPTY;
			}
			ItemStack result = Inventories.removeStack(OperationControllerBlockEntity.this.items, slot);
			markDirty();
			return result;
		}

		@Override
		public void setStack(int slot, ItemStack stack) {
			if (slot >= 0 && slot < size()) {
				OperationControllerBlockEntity.this.items.set(slot, stack);
				markDirty();
			}
		}

		@Override
		public int getMaxCountPerStack() {
			return 1;
		}

		@Override
		public void markDirty() {
			OperationControllerBlockEntity.this.markDirty();
		}

		@Override
		public boolean canPlayerUse(PlayerEntity player) {
			return OperationControllerBlockEntity.this.world != null
					&& OperationControllerBlockEntity.this.world.getBlockEntity(OperationControllerBlockEntity.this.pos) == OperationControllerBlockEntity.this
					&& player.squaredDistanceTo(OperationControllerBlockEntity.this.pos.toCenterPos()) <= 64.0;
		}

		@Override
		public void clear() {
			OperationControllerBlockEntity.this.items.clear();
			markDirty();
		}
	}
}
