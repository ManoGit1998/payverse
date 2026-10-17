package com.payverse.recon.service;

import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.model.ReconPair;

/**
 * What a pair of records means. The LEFT side is read for the business day exactly, the RIGHT
 * side with a tolerance on both ends, so "right only" counts only when the record itself falls
 * inside the day (otherwise it belongs to the neighbouring day's run).
 */
public final class MismatchClassifier {

    private MismatchClassifier() {
    }

    /** @return the mismatch type, or null if the pair reconciles */
    public static String classify(ReconJob job, ReconPair pair) {
        boolean hasLeft = pair.leftAmount() != null;
        boolean hasRight = pair.rightAmount() != null;
        if (hasLeft && hasRight) {
            return pair.leftAmount().compareTo(pair.rightAmount()) == 0 ? null : ReconJob.AMOUNT_MISMATCH;
        }
        if (hasLeft) {
            return job.leftOnlyType();
        }
        return Boolean.TRUE.equals(pair.rightInWindow()) ? job.rightOnlyType() : null;
    }
}
