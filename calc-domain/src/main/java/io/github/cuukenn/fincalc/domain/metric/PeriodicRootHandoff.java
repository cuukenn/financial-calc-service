package io.github.cuukenn.fincalc.domain.metric;

import io.github.cuukenn.fincalc.domain.model.CashFlow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The periodic roots one run has solved, each handed over at full precision together with the flow
 * set it is a root of (AGENTS.md §6 N-05: within one measurement the same root problem must not be
 * solved twice).
 *
 * <p>Two places in a run build a flow set and need its internal rate of return. The §9.5 metric
 * line solves the §9.4 event table ({@code IRR_PERIOD}) and, when the element carries an asset
 * value, the CAS 21 measure chain over that table plus the unguaranteed residual
 * ({@code LEASE_IMPLICIT_RATE}). The engine's §9.7 effective-interest chain then builds its own
 * rent-caliber stream — the first opening balance at t0, the rent legs, the terminal net investment.
 * On many deals these constructions coincide: for a fee-free, deposit-free, fully-amortizing
 * schedule the §9.7 stream <em>is</em> the event table, and under principal = asset value + IDC it
 * equals the implicit-rate stream too. Each solver therefore records what it solved here, and each
 * consumer asks {@link #rootFor(List)} before solving: a match is a root reused, no match is a
 * stream that genuinely differs and earns its own solve.
 *
 * <p>{@link MetricCodes#IRR_PERIOD} publishes its root rounded to the §9.5 scale of 10. A consumer
 * that needs the root itself — the §9.7 chain prices every row with it — cannot read the published
 * value without changing the cents every row carries (AGENTS.md §6 N-04 forbids using the 0.01 元
 * validation tolerance as a licence for that), so the unrounded answers travel beside the metric
 * bag on this channel. Recording the flow set is what makes the reuse decidable rather than
 * assumed: a consumer may read a root only when its own stream is the very one that root is a root
 * of, or its exact direction mirror (R103). When the two differ — an upfront fee, a deposit leg, a
 * ballooned 留购价, residual money the §9.4 event table does not list — the consumer solves its own
 * root, and the difference is then a stated caliber difference (CAS 21 第三十八条 prices the
 * lessor's net investment, including the unguaranteed residual, while §9.4's event table is
 * cash-only) instead of a silent duplicate.
 *
 * <p><b>Why nothing else travels on this channel.</b> AGENTS.md §6 rule 1 (先测量再优化) was applied to
 * the §9.5 line at the 480-period cell before adding any further reuse, per code
 * ({@code MetricSolveCountTest#the_metric_line_costs_are_attributable_to_each_code}): the whole line
 * is 1 180 ms of which {@code XIRR} is 970 and {@code IRR_PERIOD} 193, and every remaining one of the
 * 26 codes is under 8 ms — the column sums at 0.1 ms each, the three durations at 1.6 ms, the NPV pair
 * at 1.3 ms, the payback pair and the borrower family at 0.0 ms. A single-pass row-total carrier, a
 * shared {@code (1 + rate)^t} power ladder, a shared present-value walk and a build-once LESSEE mirror
 * would each have removed arithmetic worth 0.1–8 ms out of 1 180 — under this machine's measurement
 * noise, so none of them is implemented, and a run-scoped mutable carrier for them would be a
 * correctness risk bought for no measured time. This class carries the one hand-off a measurement
 * actually identified: whole roots over whole flow sets, where one wasted solve costs a hundred
 * evaluations.
 *
 * <p><b>Mutable by design, single-threaded by contract.</b> {@link MetricContext} presents itself as
 * an immutable view and shares this one object with every copy it makes of itself; the metrics write
 * into it during one {@link MetricRegistry#computeAll} walk on one thread, and the engine reads it
 * after that walk. A caller that ran two walks concurrently over shared contexts would interleave two
 * runs' roots on it.
 */
public final class PeriodicRootHandoff {

    private final List<Record> records = new ArrayList<>();

    /**
     * Records what a solve produced. A {@code null} root — a flow set with no sign change, so no
     * root to find — is not a hand-off and is not recorded.
     *
     * @param root  the solver's answer at full precision
     * @param flows the flow set the root is a root of; the caller's own list, not copied
     */
    public void record(BigDecimal root, List<CashFlow> flows) {
        if (root == null) {
            return;
        }
        records.add(new Record(root, flows));
    }

    /**
     * The root of the recorded flow set that is the same root problem as {@code stream} — the same
     * discount indices and amounts, or the whole-view mirror R103 defines — or {@code null} when no
     * recorded solve matches, which is the consumer's signal that its stream genuinely differs and
     * must be solved for itself.
     *
     * @param stream the consumer's own flow set, in the order it would be solved over
     * @return the matching unrounded root, or {@code null}
     */
    public BigDecimal rootFor(List<CashFlow> stream) {
        for (Record record : records) {
            if (isSameRootProblem(stream, record.flows())) {
                return record.root();
            }
        }
        return null;
    }

    /**
     * Every solve this run has recorded, in solve order.
     *
     * @return unmodifiable view; a run records at most a handful — the metric line's yields and the
     *         engine's §9.7 chain
     */
    public List<Record> records() {
        return Collections.unmodifiableList(records);
    }

    /** One recorded solve: the unrounded root and the flow set it is a root of. */
    public static final class Record {

        private final BigDecimal root;
        private final List<CashFlow> flows;

        Record(BigDecimal root, List<CashFlow> flows) {
            this.root = root;
            this.flows = flows;
        }

        /** The solver's answer at full precision — before any §9.5 rounding. */
        public BigDecimal root() {
            return root;
        }

        /** The flow set {@link #root()} is a root of, in the order it was solved over. */
        public List<CashFlow> flows() {
            return flows;
        }
    }

    /**
     * Whether two flow sets are the same root problem: the same length and, flow by flow, the same
     * discount index and the same amount — the two things {@code IrrCalculator.npv} consumes — with
     * the direction either identical throughout or inverted throughout, which is the whole-view
     * mirror R103 defines. A mirrored set negates every term of the NPV and of its derivative, so
     * the solver walks the identical path and lands on the identical root; a set that mirrors some
     * flows and not others is not a view of the same deal and is never treated as one (AGENTS.md
     * 补充口径规则 3). Amounts compare by value, so a scale difference (an element carrying
     * {@code 40000}, a terminal leg scaled to 分) is not a difference.
     */
    public static boolean isSameRootProblem(List<CashFlow> left, List<CashFlow> right) {
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        Boolean mirrored = null;
        for (int index = 0; index < left.size(); index++) {
            CashFlow fromLeft = left.get(index);
            CashFlow fromRight = right.get(index);
            if (fromLeft.sequence() != fromRight.sequence()
                    || fromLeft.amount().compareTo(fromRight.amount()) != 0) {
                return false;
            }
            boolean flipped = fromLeft.direction() != fromRight.direction();
            if (mirrored == null) {
                mirrored = flipped;
            } else if (mirrored != flipped) {
                return false;
            }
        }
        return true;
    }
}
