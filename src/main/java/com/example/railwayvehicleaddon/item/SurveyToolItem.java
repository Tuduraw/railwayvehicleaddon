package com.example.railwayvehicleaddon.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

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

	public SurveyToolItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (context.getLevel().isClientSide() && clientHandler != null && player != null) {
			clientHandler.onUse(player.isShiftKeyDown());
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		if (world.isClientSide() && clientHandler != null) {
			clientHandler.onUse(user.isShiftKeyDown());
		}
		return InteractionResult.SUCCESS;
	}
}
