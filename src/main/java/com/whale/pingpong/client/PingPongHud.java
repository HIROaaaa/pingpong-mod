package com.whale.pingpong.client;

import com.whale.pingpong.item.ModItems;
import com.whale.pingpong.item.PingPongBallItem;
import com.whale.pingpong.util.PlayerHand;
import com.whale.pingpong.util.StrokeType;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 手持球拍（或正在抛球）时在准星下方显示状态。
 *
 * 显示内容：手型、拍面俯仰、侧偏、跟球视角开关，以及蓄力条
 * —— 光看手里的模型不够直观，「角度改了 / 力度攒了多少 / 现在是正手还是反手」必须一眼可见。
 */
public final class PingPongHud {

	private static final int BAR_WIDTH = 60;
	private static final int BAR_HEIGHT = 4;
	/** 力度条宽度 */
	private static final int POWER_BAR_WIDTH = 96;

	private PingPongHud() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(PingPongHud::onHudRender);
	}

	private static void onHudRender(DrawContext context, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden) {
			return;
		}

		boolean holdingPaddle = client.player.getMainHandStack().isOf(ModItems.PINGPONG_PADDLE);
		// 抛球蓄力时也要显示力度条
		boolean chargingToss = client.player.isUsingItem()
				&& client.player.getActiveItem().isOf(ModItems.PINGPONG_BALL);
		if (!holdingPaddle && !chargingToss) {
			return;
		}

		int centerX = client.getWindow().getScaledWidth() / 2;
		int baseY = client.getWindow().getScaledHeight() - 88;

		if (chargingToss) {
			drawTossCharge(context, client, centerX, baseY);
			return;
		}

		double tilt = PingPongClientState.tilt();
		double side = PingPongClientState.sideTilt();

		// 第一行：手型 + 拍面角度（+ 跟球视角开关）
		Text handLabel = Text.translatable(PingPongClientState.hand().translationKey())
				.formatted(PingPongClientState.hand() == PlayerHand.FOREHAND ? Formatting.AQUA : Formatting.LIGHT_PURPLE);
		Text tiltLabel = Math.abs(tilt) < 0.05
				? Text.translatable("hud.pingpong.flat")
				: (tilt > 0 ? Text.translatable("hud.pingpong.fore") : Text.translatable("hud.pingpong.back"));
		Text sideLabel = Math.abs(side) < 0.05
				? Text.translatable("hud.pingpong.center")
				: (side > 0 ? Text.translatable("hud.pingpong.right") : Text.translatable("hud.pingpong.left"));

		Text firstLine = Text.translatable("hud.pingpong.status",
				handLabel,
				tiltLabel.copy().formatted(Formatting.WHITE),
				String.format("%d%%", (int) Math.round(Math.abs(tilt) * 100)),
				sideLabel.copy().formatted(Formatting.WHITE),
				String.format("%d%%", (int) Math.round(Math.abs(side) * 100)));

		context.drawCenteredTextWithShadow(client.textRenderer, firstLine, centerX, baseY, 0xFFFFFF);

		// 第二行：操作提示（跟球视角开着时点亮）
		Text camText = Text.translatable(PingPongClientState.isBallCam()
				? "hud.pingpong.ballcam.on"
				: "hud.pingpong.ballcam.off");
		camText = camText.copy().formatted(PingPongClientState.isBallCam() ? Formatting.GOLD : Formatting.DARK_GRAY);
		Text secondLine = Text.translatable("hud.pingpong.hint", camText);
		context.drawCenteredTextWithShadow(client.textRenderer, secondLine, centerX, baseY + 11, 0xAAAAAA);

		// 第三行：两根小条（俯仰 / 侧偏）
		int barY = baseY + 24;
		drawSlider(context, centerX - BAR_WIDTH - 4, barY, tilt);
		drawSlider(context, centerX + 4, barY, side);

		// 蓄力条：左键按住时出现（需求 1）
		if (PingPongClientState.isCharging()) {
			drawPowerBar(context, client, centerX, barY + 9, PingPongClientState.chargeRatio());
		}

		// 第四行：这一拍会打出什么（需求 17/20b：右键蓄力过半变削球，玩家必须看得见）
		StrokeType preview = PingPongClient.previewStroke(client);
		if (preview != null) {
			Text strokeText = Text.translatable("hud.pingpong.stroke",
							Text.translatable(preview.translationKey))
					.formatted(preview.isChop() ? Formatting.DARK_PURPLE
							: preview.isPush() ? Formatting.AQUA
							: preview.isLoop() ? Formatting.GOLD : Formatting.WHITE);
			context.drawCenteredTextWithShadow(client.textRenderer, strokeText, centerX, barY + 18, 0xFFFFFF);
		}
	}

	/** 抛球蓄力条（需求 5）。 */
	private static void drawTossCharge(DrawContext context, MinecraftClient client, int centerX, int baseY) {
		PlayerEntity player = client.player;
		int usedTicks = player.getItemUseTime();
		double ratio = PingPongBallItem.chargeRatio(usedTicks);

		context.drawCenteredTextWithShadow(client.textRenderer,
				Text.translatable("hud.pingpong.toss_charge", (int) Math.round(ratio * 100.0))
						.formatted(Formatting.YELLOW),
				centerX, baseY, 0xFFFFFF);
		drawPowerBar(context, client, centerX, baseY + 14, ratio);
	}

	/** 以中点为 0 的滑块，正数向右。 */
	private static void drawSlider(DrawContext context, int x, int y, double value) {
		context.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x80000000);
		int mid = x + BAR_WIDTH / 2;
		context.fill(mid, y, mid + 1, y + BAR_HEIGHT, 0xFFAAAAAA);

		int offset = (int) Math.round(value * (BAR_WIDTH / 2.0 - 1));
		if (offset >= 0) {
			context.fill(mid, y, mid + offset, y + BAR_HEIGHT, 0xFF55FFFF);
		} else {
			context.fill(mid + offset, y, mid, y + BAR_HEIGHT, 0xFFFF55FF);
		}
	}

	/** 从左往右填充的力度条，颜色随力度由青转金转红。 */
	private static void drawPowerBar(DrawContext context, MinecraftClient client, int centerX, int y, double ratio) {
		int left = centerX - POWER_BAR_WIDTH / 2;
		context.fill(left, y, left + POWER_BAR_WIDTH, y + BAR_HEIGHT, 0x90000000);
		int filled = (int) Math.round(POWER_BAR_WIDTH * Math.max(0.0, Math.min(1.0, ratio)));
		if (filled > 0) {
			int color = ratio < 0.5 ? 0xFF55FFFF : (ratio < 0.85 ? 0xFFFFD700 : 0xFFFF5555);
			context.fill(left, y, left + filled, y + BAR_HEIGHT, color);
		}
	}
}
