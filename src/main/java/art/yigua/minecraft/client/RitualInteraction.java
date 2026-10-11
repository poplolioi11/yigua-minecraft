package art.yigua.minecraft.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** 拖曳的草稿狀態，不抽卦、不決定錢面，也不改動伺服器的策數。 */
public final class RitualInteraction {
    /** 26.3 的 SDL 按鍵編號從 1 起算；與舊 GLFW 的 0 不同。 */
    public static boolean isPrimaryButton(int button) { return button == 1; }

    public record Rect(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }
    public record Stick(int id, boolean left, Rect bounds, boolean selected, boolean carried) {}

    private int remaining;
    private final BitSet left = new BitSet(49);
    private final BitSet selected = new BitSet(49);
    private Rect leftZone = new Rect(0, 0, 1, 1);
    private Rect rightZone = new Rect(2, 0, 1, 1);
    private boolean carrying;
    private boolean selecting;
    private boolean selectionSide;
    private double startX, startY, pointerX, pointerY;

    public void reset(int count) {
        if (count < 0 || count > 49) throw new IllegalArgumentException("策數超出範圍");
        remaining = count;
        left.clear();
        selected.clear();
        cancelGesture();
    }

    public void layout(Rect leftZone, Rect rightZone) {
        if (leftZone.width() < 1 || leftZone.height() < 1 || rightZone.width() < 1 || rightZone.height() < 1) {
            throw new IllegalArgumentException("分堆區必須有可操作的大小");
        }
        this.leftZone = leftZone;
        this.rightZone = rightZone;
    }

    /** 在文字／拖曳介面之間保留尚未提交的左堆草稿。 */
    public void setLeftCount(int count) {
        if (count < 0 || count > remaining) throw new IllegalArgumentException("左堆超出策數");
        left.clear();
        left.set(0, count);
        selected.clear();
        cancelGesture();
    }

    public int leftCount() { return left.cardinality(); }
    public int rightCount() { return remaining - leftCount(); }
    public int remaining() { return remaining; }
    public boolean validSplit() { return leftCount() >= 1 && rightCount() >= 2; }
    public boolean carrying() { return carrying; }
    public boolean selecting() { return selecting; }
    public int selectedCount() { return selected.cardinality(); }
    public double pointerX() { return pointerX; }
    public double pointerY() { return pointerY; }

    public List<Stick> sticks() {
        List<Stick> result = new ArrayList<>(remaining);
        int leftIndex = 0, rightIndex = 0;
        for (int id = 0; id < remaining; id++) {
            boolean side = left.get(id);
            int slot = side ? leftIndex++ : rightIndex++;
            Rect zone = side ? leftZone : rightZone;
            double cellWidth = (zone.width() - 8) / 7.0;
            double cellHeight = (zone.height() - 8) / 7.0;
            double stickWidth = Math.max(2, Math.min(5, cellWidth - 2));
            double stickHeight = Math.max(3, Math.min(22, cellHeight - 2));
            Rect bounds = new Rect(zone.x() + 4 + (slot % 7) * cellWidth + (cellWidth - stickWidth) / 2,
                zone.y() + 4 + (slot / 7) * cellHeight, stickWidth, stickHeight);
            result.add(new Stick(id, side, bounds, selected.get(id), carrying && selected.get(id)));
        }
        return result;
    }

    /** Shift 拖曳或從空白處拖曳可框選；再拖曳任一選中竹籌就搬動整批。 */
    public boolean press(double x, double y, boolean shift) {
        if (carrying || selecting || remaining == 0) return false;
        Boolean side = sideAt(x, y);
        if (side == null) return false;
        pointerX = startX = x;
        pointerY = startY = y;
        Stick hit = null;
        if (!shift) {
            for (Stick stick : sticks()) if (stick.bounds().contains(x, y)) { hit = stick; break; }
        }
        if (hit != null) {
            if (!selected.get(hit.id())) {
                selected.clear();
                selected.set(hit.id());
            }
            carrying = true;
        } else {
            selected.clear();
            selectionSide = side;
            selecting = true;
        }
        return true;
    }

    public boolean move(double x, double y) {
        if (!carrying && !selecting) return false;
        pointerX = x;
        pointerY = y;
        if (selecting) updateSelection();
        return true;
    }

    /** 放到另一堆才搬動；無效的分堆保留原樣，確認按鈕不送出非法策數。 */
    public boolean release(double x, double y) {
        if (!carrying && !selecting) return false;
        move(x, y);
        if (carrying) {
            Boolean target = sideAt(x, y);
            if (target != null) {
                for (int id = selected.nextSetBit(0); id >= 0; id = selected.nextSetBit(id + 1)) left.set(id, target);
            }
        }
        cancelGesture();
        return true;
    }

    public void cancelGesture() { carrying = false; selecting = false; }

    public Rect selectionRect() {
        return new Rect(Math.min(startX, pointerX), Math.min(startY, pointerY),
            Math.abs(pointerX - startX), Math.abs(pointerY - startY));
    }

    private void updateSelection() {
        Rect area = selectionRect();
        selected.clear();
        for (Stick stick : sticks()) {
            Rect bounds = stick.bounds();
            if (stick.left() == selectionSide && area.contains(bounds.x() + bounds.width() / 2,
                    bounds.y() + bounds.height() / 2)) selected.set(stick.id());
        }
    }

    private Boolean sideAt(double x, double y) {
        if (leftZone.contains(x, y)) return true;
        if (rightZone.contains(x, y)) return false;
        return null;
    }

    /** 拖出一枚錢，放入投擲區才產生一次請求；錢面仍只能由伺服器回傳。 */
    public static final class CoinThrow {
        private boolean carrying;
        private double x, y;
        public boolean press(Rect coin, double x, double y) {
            if (carrying || !coin.contains(x, y)) return false;
            carrying = true;
            this.x = x;
            this.y = y;
            return true;
        }
        public boolean move(double x, double y) {
            if (!carrying) return false;
            this.x = x;
            this.y = y;
            return true;
        }
        public boolean release(Rect target, double x, double y) {
            if (!carrying) return false;
            move(x, y);
            carrying = false;
            return target.contains(x, y);
        }
        public void cancel() { carrying = false; }
        public boolean carrying() { return carrying; }
        public double x() { return x; }
        public double y() { return y; }
    }
}
