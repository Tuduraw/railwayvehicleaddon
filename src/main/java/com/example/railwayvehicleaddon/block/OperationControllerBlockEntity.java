package com.example.railwayvehicleaddon.block;

import com.example.railwayvehicleaddon.entity.RailVehicleEntity;
import com.example.railwayvehicleaddon.item.RouteCardItem;
import com.example.railwayvehicleaddon.operation.OperationState;
import com.example.railwayvehicleaddon.operation.Timetable;
import com.example.railwayvehicleaddon.screen.OperationControllerScreenHandler;
import com.example.tudursvehiclemod.ChunkForceTracker;
import com.example.tudursvehiclemod.item.DroneControlStickItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

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
public class OperationControllerBlockEntity extends BlockEntity implements MenuProvider {
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

	private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
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

	public Container inventory() {
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
		setChanged();
		return true;
	}

	private RailVehicleEntity assignedCar(int row) {
		if (!(this.level instanceof ServerLevel serverWorld) || row < 0 || row >= this.rows) {
			return null;
		}
		UUID id = registeredVehicle(stick(row));
		if (id != null && serverWorld.getEntity(id) instanceof RailVehicleEntity car && this.worldPosition.equals(car.managedBy()) && car.managedRow() == row) {
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
		NonNullList<ItemStack> resized = NonNullList.withSize(next * 2, ItemStack.EMPTY);
		for (int i = 0; i < this.items.size(); i++) {
			if (i < resized.size()) {
				resized.set(i, this.items.get(i));
			} else if (!this.items.get(i).isEmpty() && this.level != null) {
				Containers.dropItemStack(this.level, this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 1.0, this.worldPosition.getZ() + 0.5, this.items.get(i));
			}
		}
		if (this.level instanceof ServerLevel serverWorld) {
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

	public static void tick(Level world, BlockPos pos, BlockState state, OperationControllerBlockEntity entity) {
		if (!(world instanceof ServerLevel serverWorld)) {
			return;
		}
		if (!entity.ownChunkRequested) {
			entity.updateOwnChunk(true);
		}
		long now = serverWorld.getGameTime();
		for (int r = 0; r < entity.rows; r++) {
			if ((now + r) % PROCESS_INTERVAL == 0) {
				entity.processRow(serverWorld, r, now);
			}
		}
	}

	private void processRow(ServerLevel world, int r, long now) {
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
		if (car.isManaged() && !(this.worldPosition.equals(car.managedBy()) && car.managedRow() == r) && otherStillAssigns(world, car)) {
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
		if (!this.worldPosition.equals(car.managedBy()) || car.managedRow() != r || car.managedHash() != hash || car.getTimetable() == null) {
			for (RailVehicleEntity other : consistCars) {
				if (other != car && other.getTimetable() != null) {
					other.clearTimetable();
				}
			}
			car.assignTimetable(table, this.worldPosition, r, hash);
		}

		// チャンクの読み込み(編成の全車両の周りと、先読みの経路)
		OperationState state = OperationState.get(world);
		OperationState.Consist consist = state.consistOf(car.getUUID(), now);
		Set<ChunkPos> want = new HashSet<>(consist != null ? state.loadChunks(consist.key()) : Set.of());
		List<ChunkPos> seen = new ArrayList<>();
		for (RailVehicleEntity member : consistCars) {
			ChunkPos c = member.chunkPosition();
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
			setChanged();
		}
		row.status = car.atoState().enabled ? STATUS_RUNNING : STATUS_MANUAL;
		row.display = car.atoState().display.ordinal();
		row.stopIndex = car.stopIndex();
	}

	/** 車両carを割り当てている装置が、今もcarを割り当てているか。 */
	private static boolean otherStillAssigns(ServerLevel world, RailVehicleEntity car) {
		BlockPos other = car.managedBy();
		if (other == null || !world.hasChunkAt(other)) {
			return other != null;
		}
		return world.getBlockEntity(other) instanceof OperationControllerBlockEntity controller
				&& controller.assigns(car.managedRow(), car.getUUID());
	}

	/** 見つからない編成を、最後に見えた場所を一時的に読み込んで探す。 */
	private void search(ServerLevel world, int r) {
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

	private void releaseSearch(ServerLevel world, int r) {
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

	private void releaseChunks(ServerLevel world, int r) {
		Row row = this.rowStates.get(r);
		Object requester = rowRequester(r);
		for (ChunkPos c : row.forced) {
			ChunkForceTracker.release(world, c, requester);
		}
		row.forced = Set.of();
	}

	/** 組rの割り当て(読み込まれていれば車両の運行表)とチャンクの読み込みを外す。 */
	private void releaseAssignment(ServerLevel world, int r) {
		Row row = this.rowStates.get(r);
		if (row.vehicle != null && world.getEntity(row.vehicle) instanceof RailVehicleEntity car
				&& this.worldPosition.equals(car.managedBy()) && car.managedRow() == r) {
			car.clearTimetable();
		}
		row.vehicle = null;
		row.display = 0;
		row.stopIndex = 0;
		releaseChunks(world, r);
		releaseSearch(world, r);
		if (!row.lastKnown.isEmpty()) {
			row.lastKnown = List.of();
			setChanged();
		}
	}

	private void releaseRow(ServerLevel world, int r) {
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
		if (this.level instanceof ServerLevel serverWorld) {
			ChunkPos own = new ChunkPos(this.worldPosition);
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
	public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
		if (this.level instanceof ServerLevel serverWorld) {
			for (int r = 0; r < this.rows; r++) {
				releaseAssignment(serverWorld, r);
			}
			Containers.dropContents(serverWorld, pos, this.items);
			this.items.clear();
			ChunkForceTracker.releaseAllOwnedBy(serverWorld, this);
		}
		super.preRemoveSideEffects(pos, oldState);
	}

	/** チャンクの読み込みが外れた(サーバーの停止など): 読み込みの記録だけ忘れる(強制読み込みはワールドと一緒に保存される)。 */
	@Override
	public void setRemoved() {
		super.setRemoved();
		if (this.level instanceof ServerLevel serverWorld) {
			ChunkForceTracker.forgetAllOwnedBy(serverWorld, this);
		}
	}

	// ------------------------------------------------------------------ 保存

	@Override
	protected void loadAdditional(ValueInput view) {
		super.loadAdditional(view);
		this.rows = Math.max(1, Math.min(MAX_ROWS, view.getIntOr("Rows", 1)));
		this.items = NonNullList.withSize(this.rows * 2, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(view, this.items);
		this.page = Math.max(0, Math.min(pageCount() - 1, view.getIntOr("Page", 0)));
		this.rowStates.clear();
		ensureRowStates();
		int[] known = view.getIntArray("LastKnown").orElse(new int[0]);
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
	protected void saveAdditional(ValueOutput view) {
		super.saveAdditional(view);
		view.putInt("Rows", this.rows);
		view.putInt("Page", this.page);
		ContainerHelper.saveAllItems(view, this.items, true);
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
	public Component getDisplayName() {
		return Component.translatable("container.railwayvehicleaddon.operation_controller");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
		return new OperationControllerScreenHandler(syncId, playerInventory, this);
	}

	/** 画面へ同期する値(組の数・ページ・ページ内の各組の状態・ATOの状態・次の駅の番号)。 */
	public ContainerData properties() {
		return new ContainerData() {
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
			public int getCount() {
				return OperationControllerScreenHandler.PROPERTY_COUNT;
			}
		};
	}

	/** スロット全体(組r: 2r=スティック、2r+1=経路アイテム)。 */
	public final class RowsInventory implements Container {
		@Override
		public int getContainerSize() {
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
		public ItemStack getItem(int slot) {
			return slot >= 0 && slot < getContainerSize() ? OperationControllerBlockEntity.this.items.get(slot) : ItemStack.EMPTY;
		}

		@Override
		public ItemStack removeItem(int slot, int amount) {
			if (slot < 0 || slot >= getContainerSize()) {
				return ItemStack.EMPTY;
			}
			ItemStack result = ContainerHelper.removeItem(OperationControllerBlockEntity.this.items, slot, amount);
			if (!result.isEmpty()) {
				setChanged();
			}
			return result;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			if (slot < 0 || slot >= getContainerSize()) {
				return ItemStack.EMPTY;
			}
			ItemStack result = ContainerHelper.takeItem(OperationControllerBlockEntity.this.items, slot);
			setChanged();
			return result;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			if (slot >= 0 && slot < getContainerSize()) {
				OperationControllerBlockEntity.this.items.set(slot, stack);
				setChanged();
			}
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public void setChanged() {
			OperationControllerBlockEntity.this.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return OperationControllerBlockEntity.this.level != null
					&& OperationControllerBlockEntity.this.level.getBlockEntity(OperationControllerBlockEntity.this.worldPosition) == OperationControllerBlockEntity.this
					&& player.distanceToSqr(OperationControllerBlockEntity.this.worldPosition.getCenter()) <= 64.0;
		}

		@Override
		public void clearContent() {
			OperationControllerBlockEntity.this.items.clear();
			setChanged();
		}
	}
}
