package com.example.railwayvehicleaddon.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * 測量ツール。操作状態(モード・点・選択)とプレビューはクライアント側(SurveySession)で扱い、
 * 確定・撤去・分岐器切替の要求だけをサーバーへ送る。
 * 右クリックは、ブロックを狙っていても空中でも、クライアント側で独自に遠距離の視線判定を行う
 * (通常のブロック操作の届く距離では経由点を置きにくいため)。操作の詳細はREADME参照。
 */
public class SurveyToolItem extends Item {

	/** クライアント側の処理への橋渡し。共通コードからクライアント専用クラスを参照しないため、クライアント初期化時に設定する。 */
	public interface ClientHandler {
		void onUse(boolean sneaking);
	}

	public static ClientHandler clientHandler;

	public SurveyToolItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		PlayerEntity player = context.getPlayer();
		if (context.getWorld().isClient() && clientHandler != null && player != null) {
			clientHandler.onUse(player.isSneaking());
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		if (world.isClient() && clientHandler != null) {
			clientHandler.onUse(user.isSneaking());
		}
		return ActionResult.SUCCESS;
	}
}
