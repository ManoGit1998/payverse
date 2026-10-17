package com.payverse.fraud.service;

import com.payverse.fraud.model.RiskContext;

import java.util.OptionalInt;

/**
 * Port for a future ML scorer (e.g. a gradient-boosted model served over HTTP). The final score is
 * the higher of the rule score and the model score, so a model can only add caution.
 */
public interface RiskModel {

    /** @return a 0–100 score, or empty if the model has no opinion */
    OptionalInt score(RiskContext context);

    String version();
}
