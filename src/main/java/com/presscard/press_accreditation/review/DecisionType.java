package com.presscard.press_accreditation.review;

/** What a reviewer decided (V1.3 §G). */
public enum DecisionType {
    APPROVE("Acceptée"),
    REJECT("Rejetée"),
    REQUEST_CORRECTION("Correction demandée");

    private final String labelFr;

    DecisionType(String labelFr) {
        this.labelFr = labelFr;
    }

    public String labelFr() {
        return labelFr;
    }
}
