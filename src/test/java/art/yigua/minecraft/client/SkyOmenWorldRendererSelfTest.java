package art.yigua.minecraft.client;

import net.minecraft.world.phys.Vec3;

/** 核對不可逆開雲／下降、世界座標快照與幾何預算；不建立視窗或 GPU。 */
public final class SkyOmenWorldRendererSelfTest {
    private static int checks;
    private SkyOmenWorldRendererSelfTest() {}

    public static void verify() {
        checks = 0;
        double previousGap = -1;
        double previousBottom = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick <= 120; tick++) {
            double gap = SkyOmenWorldRenderer.divineGap(tick);
            double bottom = SkyOmenWorldRenderer.beamBottom(tick);
            require(Double.isFinite(gap) && gap >= previousGap && gap >= 0 && gap <= 28,
                "左右散雲需平滑向外且有界");
            require(Double.isFinite(bottom) && bottom <= previousBottom && bottom >= 0 && bottom <= 32,
                "神光下緣只能從高空下降且不能穿過底部");
            previousGap = gap;
            previousBottom = bottom;
        }
        require(SkyOmenWorldRenderer.divineGap(0) == 0, "神光一開始雲仍聚合");
        require(SkyOmenWorldRenderer.divineGap(70) == 28, "神光七十刻後開出中央通道");
        require(SkyOmenWorldRenderer.beamBottom(0) == 32, "神光應由三十二格高處下降");
        require(SkyOmenWorldRenderer.beamBottom(50) == 16, "神光中段仍在下降");
        require(SkyOmenWorldRenderer.beamBottom(100) == 0, "神光一百刻後抵達底部");
        for (int duration : new int[] {1, 2, 3, 120, 160, 1200}) {
            require(SkyOmenWorldRenderer.fade(0, duration) == 0, "起始需淡入");
            require(SkyOmenWorldRenderer.fade(duration, duration) == 0, "結束需淡出");
            for (double time : new double[] {-100, -1, 0, 0.5, duration / 2.0, duration, duration + 1}) {
                double value = SkyOmenWorldRenderer.fade(time, duration);
                require(Double.isFinite(value) && value >= 0 && value <= 1, "異象透明度越界");
            }
        }
        require(SkyOmenWorldRenderer.fade(Double.NaN, 120) == 0, "非法時間不可提交可見幾何");
        Vec3 anchor = new Vec3(30_000_000, -64, -30_000_000);
        for (String mode : new String[] {"dark", "divine"}) {
            for (long seed : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
                for (int tick : new int[] {0, 25, 50, 70, 100, 120}) {
                    var frame = SkyOmenWorldRenderer.frame(mode, seed, tick, 160, anchor, 137);
                    require(frame.anchor().equals(anchor) && frame.angle() == 137,
                        "世界位置與初始視線必須固定，不可貼附 HUD");
                    require(frame.clouds().size() <= 363 && !frame.clouds().isEmpty(), "雲冠超出幾何預算");
                    require(frame.equals(SkyOmenWorldRenderer.frame(mode, seed, tick, 160, anchor, 137)),
                        "相同封包與時間需生成穩定雲冠");
                    for (var box : frame.clouds()) {
                        require(Double.isFinite(box.x()) && Double.isFinite(box.y()) && Double.isFinite(box.z())
                                && box.width() > 0 && box.height() > 0 && box.depth() > 0,
                            "雲冠需為有限的世界立體區塊");
                        if ("divine".equals(mode) && tick >= 70) {
                            require(box.x() + box.width() < 0 || box.x() > 0, "左右散雲後中央神光通道需保持開放");
                        }
                    }
                    try {
                        frame.clouds().clear();
                        throw new AssertionError("渲染快照不可被後續修改");
                    } catch (UnsupportedOperationException expected) { checks++; }
                }
            }
        }
        require(SkyOmenWorldRenderer.frame("flowers", 0, 5, 120, anchor, 0) == SkyOmenWorldRenderer.Frame.EMPTY,
            "非世界雲模式不應重複提交幾何");
        System.out.println("SkyOmenWorldRendererSelfTest：" + checks + " 項世界異象幾何核對通過。");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
