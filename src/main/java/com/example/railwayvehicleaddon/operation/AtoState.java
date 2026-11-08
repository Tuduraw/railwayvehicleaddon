package com.example.railwayvehicleaddon.operation;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 運行表を持つ車両(編成の運転を決める車両)のATOの状態。車両に保存する。
 */
public final class AtoState {
	public enum Phase {
		/** 走行(次の駅へ向かう) */
		RUN,
		/** 駅に停車中(停車時間を数えている) */
		DWELL,
		/** 停車時間は過ぎたが、発車時刻を待っている */
		WAIT_DEPART,
		/** 運行表の最後の駅に着いた(終点: 停止) */
		FINISHED,
		/** 次の駅の停止位置目標への道が見つからない */
		NO_ROUTE
	}

	/** 表示用の状態(HUD変数 rail_ato_state・運行管理装置の表示) */
	public enum Display {
		OFF, POWER, COAST, BRAKE, DWELL, WAIT_DEPART, FINISHED, NO_ROUTE, HELD
	}

	/** ATOで運転するか(運行管理装置で割り当てると入る。運転者がノッチ・ブレーキを操作すると切れる) */
	public boolean enabled;
	public Phase phase = Phase.RUN;
	/** 進む向き(この車両の前方なら+1、後方なら-1、0はまだ決めていない) */
	public int dir;
	/** 停車時間の終わり(ワールド時刻)。停車中でなければ-1 */
	public long dwellUntil = -1L;
	/** 止まったまま動けない時間(tick)。表示用 */
	public int held;
	/** 表示用の値 */
	public Display display = Display.OFF;
	public double limitKmh = -1.0;
	public double nextDistance = -1.0;

	/** 運行表を割り当て直した・ATOを入れ直したとき: 最初から判定し直す。 */
	public void restart() {
		this.phase = Phase.RUN;
		this.dir = 0;
		this.dwellUntil = -1L;
		this.held = 0;
	}

	public void read(ValueInput view) {
		this.enabled = view.getBooleanOr("AtoEnabled", false);
		int p = view.getIntOr("AtoPhase", 0);
		this.phase = Phase.values()[Math.max(0, Math.min(Phase.values().length - 1, p))];
		this.dir = Integer.signum(view.getIntOr("AtoDir", 0));
		this.dwellUntil = view.getLongOr("AtoDwellUntil", -1L);
	}

	public void write(ValueOutput view) {
		view.putBoolean("AtoEnabled", this.enabled);
		view.putInt("AtoPhase", this.phase.ordinal());
		view.putInt("AtoDir", this.dir);
		view.putLong("AtoDwellUntil", this.dwellUntil);
	}
}
