package forge.gen;

/** The random draws of one cell (column key and row). Draws are numbered from 1 and never depend on other cells. */
final class Cell {

    private final long start;
    private int used;

    Cell(long key, long row) {
        start = Mix.cellStart(key, row);
    }

    long next() {
        used++;
        return Mix.draw(start, used);
    }

    /** A number in [0, n), n above 0: the high half of draw times n. No loop, no floating point. */
    long below(long n) {
        return Math.unsignedMultiplyHigh(next(), n);
    }

    /** True at a rate given in parts per 10,000. */
    boolean chance(int per10k) {
        return below(10_000) < per10k;
    }
}
