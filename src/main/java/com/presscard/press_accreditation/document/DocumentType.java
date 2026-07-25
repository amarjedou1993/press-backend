package com.presscard.press_accreditation.document;

/**
 * The four kinds of supporting evidence (V1.3 §D), matching the CHECK
 * constraints on application_documents and document_requirements.
 *
 * Two are FILES (uploaded), two are LINKS (typed URLs) — the distinction the
 * DB enforces via document_kind_consistency.
 */
public enum DocumentType {
    CONTRACT("Contrat de travail", "عقد العمل", Kind.FILE),
    WORK_CERTIFICATE("Attestation de travail", "شهادة عمل", Kind.FILE),
    WEBSITE("Site web professionnel", "موقع إلكتروني", Kind.LINK),
    WORK_LINK("Lien de publication", "رابط منشور", Kind.LINK);

    public enum Kind { FILE, LINK }

    private final String labelFr;
    private final String labelAr;
    private final Kind kind;

    DocumentType(String labelFr, String labelAr, Kind kind) {
        this.labelFr = labelFr;
        this.labelAr = labelAr;
        this.kind = kind;
    }

    public String labelFr() { return labelFr; }
    public String labelAr() { return labelAr; }
    public Kind kind() { return kind; }
    public boolean isFile() { return kind == Kind.FILE; }
}
