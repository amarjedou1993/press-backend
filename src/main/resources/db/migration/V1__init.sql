-- ═══════════════════════════════════════════════════════════════════
-- V1__init.sql — HAPA Press Card Accreditation (Requirements V1.3)
-- Schema owner: Flyway. Hibernate runs with ddl-auto=validate.
-- Design rule: every business invariant that CAN live in the database
-- DOES live in the database. Java enforces it too, but the DB is the
-- last line of defense and the legal record.
-- ═══════════════════════════════════════════════════════════════════

-- ───────────────────────────────────────────────────────────────────
-- updated_at maintenance
-- ───────────────────────────────────────────────────────────────────
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ───────────────────────────────────────────────────────────────────
-- users
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(100),
    role           VARCHAR(20)  NOT NULL
                   CHECK (role IN ('CANDIDATE', 'REVIEWER', 'SUPER_ADMIN')),
    full_name      VARCHAR(200) NOT NULL,
    phone          VARCHAR(30),
    -- Dormant V2 seams (Khidmaty). All V1 rows are LOCAL.
    user_source    VARCHAR(20)  NOT NULL DEFAULT 'LOCAL'
                   CHECK (user_source IN ('LOCAL', 'KHIDMATY')),
    khidmaty_id    VARCHAR(100) UNIQUE,
    has_password   BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- has_password and password_hash must agree
    CONSTRAINT users_password_consistency
        CHECK ( (has_password AND password_hash IS NOT NULL)
             OR (NOT has_password AND password_hash IS NULL) )
);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ───────────────────────────────────────────────────────────────────
-- candidate_profiles (1:0..1 with users)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE candidate_profiles (
    user_id     BIGINT PRIMARY KEY REFERENCES users (id),
    nni         VARCHAR(20) UNIQUE,          -- checksum validated in Java (@ValidNni)
    passport_no VARCHAR(30),
    birthdate   DATE         NOT NULL,
    birthplace  VARCHAR(200) NOT NULL,
    -- V1.3 §D: national ID (NNI) OR passport
    CONSTRAINT candidate_identity_present
        CHECK (nni IS NOT NULL OR passport_no IS NOT NULL)
);

-- ───────────────────────────────────────────────────────────────────
-- sessions
-- V1.3 §F: total duration divided across four phases; the derived
-- boundary dates are targets — phases close by explicit admin action
-- (status column), except the correction-deadline auto-rejection job.
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE sessions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type            VARCHAR(20) NOT NULL DEFAULT 'CANDIDACY'
                    CHECK (type IN ('CANDIDACY')),   -- extensible in V2
    start_date      DATE        NOT NULL,
    total_days      INT         NOT NULL CHECK (total_days > 0),
    receiving_end   DATE        NOT NULL,
    review_end      DATE        NOT NULL,
    correction_end  DATE        NOT NULL,
    reclamation_end DATE        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PLANNED'
                    CHECK (status IN ('PLANNED', 'RECEIVING', 'REVIEW',
                                      'CORRECTION', 'RECLAMATION', 'CLOSED')),
    created_by      BIGINT      NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- phase boundaries are ordered and consistent with the total
    CONSTRAINT session_phases_ordered
        CHECK (start_date < receiving_end
           AND receiving_end <= review_end
           AND review_end <= correction_end
           AND correction_end <= reclamation_end),
    CONSTRAINT session_phases_sum_to_total
        CHECK (reclamation_end - start_date = total_days)
);

-- ───────────────────────────────────────────────────────────────────
-- press_categories + document_requirements (seed data in V2 migration)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE press_categories (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code     VARCHAR(40)  NOT NULL UNIQUE,
    label_fr VARCHAR(200) NOT NULL,
    label_ar VARCHAR(200) NOT NULL
);

CREATE TABLE document_requirements (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id       BIGINT      NOT NULL REFERENCES press_categories (id),
    doc_type          VARCHAR(30) NOT NULL
                      CHECK (doc_type IN ('CONTRACT', 'WORK_CERTIFICATE',
                                          'WEBSITE', 'WORK_LINK')),
    min_count         INT         NOT NULL DEFAULT 1 CHECK (min_count > 0),
    -- NULL  = mandatory requirement
    -- same non-null group = alternatives, satisfying ANY ONE suffices
    alternative_group INT,
    UNIQUE (category_id, doc_type)
);

-- ───────────────────────────────────────────────────────────────────
-- applications — the 9-state machine (V1.3 §E/§G)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE applications (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_id     BIGINT      NOT NULL REFERENCES users (id),
    session_id       BIGINT      NOT NULL REFERENCES sessions (id),
    category_id      BIGINT      NOT NULL REFERENCES press_categories (id),
    status           VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'UNDER_REVIEW',
                                       'CORRECTION_REQUESTED', 'UNDER_FINAL_REVIEW',
                                       'ACCEPTED', 'REJECTED', 'UNDER_RECLAMATION',
                                       'FINAL_REJECTION', 'CARD_ISSUED')),
    claimed_by       BIGINT      REFERENCES users (id),   -- reviewer lock
    correction_count INT         NOT NULL DEFAULT 0,
    submitted_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- V1.3: one application per candidate per session
    CONSTRAINT one_application_per_candidate_per_session
        UNIQUE (candidate_id, session_id),
    -- V1.3 §H: one correction round maximum
    CONSTRAINT max_one_correction_round
        CHECK (correction_count BETWEEN 0 AND 1)
);

CREATE TRIGGER trg_applications_updated_at
    BEFORE UPDATE ON applications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- reviewer pool query: unclaimed applications awaiting review
CREATE INDEX idx_applications_review_pool
    ON applications (session_id)
    WHERE status = 'UNDER_REVIEW' AND claimed_by IS NULL;

-- reviewer worklist
CREATE INDEX idx_applications_claimed_by
    ON applications (claimed_by)
    WHERE claimed_by IS NOT NULL;

-- correction-deadline auto-rejection job
CREATE INDEX idx_applications_correction_pending
    ON applications (session_id)
    WHERE status = 'CORRECTION_REQUESTED';

-- ───────────────────────────────────────────────────────────────────
-- application_documents (files and links, versioned)
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE application_documents (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id   BIGINT      NOT NULL REFERENCES applications (id),
    doc_type         VARCHAR(30) NOT NULL
                     CHECK (doc_type IN ('CONTRACT', 'WORK_CERTIFICATE',
                                         'WEBSITE', 'WORK_LINK')),
    kind             VARCHAR(10) NOT NULL CHECK (kind IN ('FILE', 'LINK')),
    file_path        VARCHAR(500),
    url              VARCHAR(1000),
    needs_correction BOOLEAN     NOT NULL DEFAULT FALSE,
    observation      TEXT,
    version          INT         NOT NULL DEFAULT 1 CHECK (version > 0),
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- a FILE has a path and no url; a LINK has a url and no path
    CONSTRAINT document_kind_consistency
        CHECK ( (kind = 'FILE' AND file_path IS NOT NULL AND url IS NULL)
             OR (kind = 'LINK' AND url IS NOT NULL AND file_path IS NULL) )
);

CREATE INDEX idx_application_documents_app
    ON application_documents (application_id);

-- ───────────────────────────────────────────────────────────────────
-- review_decisions — immutable audit of every reviewer decision
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE review_decisions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL REFERENCES applications (id),
    reviewer_id    BIGINT      NOT NULL REFERENCES users (id),
    decision       VARCHAR(30) NOT NULL
                   CHECK (decision IN ('APPROVE', 'REJECT', 'REQUEST_CORRECTION')),
    justification  TEXT,
    round          VARCHAR(20) NOT NULL
                   CHECK (round IN ('INITIAL', 'FINAL', 'RECLAMATION')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- exactly one decision per round
    CONSTRAINT one_decision_per_round UNIQUE (application_id, round),
    -- V1.3 §G: rejection always carries a justification
    CONSTRAINT reject_requires_justification
        CHECK (decision <> 'REJECT' OR justification IS NOT NULL),
    -- correction may only be requested in the INITIAL round (§G.4, §I)
    CONSTRAINT correction_only_in_initial_round
        CHECK (round = 'INITIAL' OR decision <> 'REQUEST_CORRECTION')
);

-- V1.3 §I.2 — the reclamation is examined by a DIFFERENT reviewer than
-- the author of the rejection. Cross-row rule → enforced by trigger.
CREATE FUNCTION check_reclamation_reviewer() RETURNS trigger AS $$
DECLARE
    rejecter BIGINT;
BEGIN
    IF NEW.round = 'RECLAMATION' THEN
        SELECT reviewer_id INTO rejecter
        FROM review_decisions
        WHERE application_id = NEW.application_id
          AND decision = 'REJECT'
        ORDER BY created_at DESC
        LIMIT 1;

        IF rejecter IS NOT NULL AND rejecter = NEW.reviewer_id THEN
            RAISE EXCEPTION
                'Reclamation on application % must be examined by a different reviewer than the one who rejected it (reviewer %)',
                NEW.application_id, NEW.reviewer_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reclamation_different_reviewer
    BEFORE INSERT ON review_decisions
    FOR EACH ROW EXECUTE FUNCTION check_reclamation_reviewer();

-- ───────────────────────────────────────────────────────────────────
-- objection_reasons (seed pending HAPA) + objections
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE objection_reasons (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label_fr VARCHAR(300) NOT NULL,
    label_ar VARCHAR(300) NOT NULL,
    active   BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE objections (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- V1.3 §I.1: the once-only objection right, as a constraint
    application_id BIGINT      NOT NULL UNIQUE REFERENCES applications (id),
    reason_id      BIGINT      NOT NULL REFERENCES objection_reasons (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ───────────────────────────────────────────────────────────────────
-- cards
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE cards (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT       NOT NULL UNIQUE REFERENCES applications (id),
    card_number    VARCHAR(40)  NOT NULL UNIQUE,
    issued_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     DATE         NOT NULL,
    pdf_path       VARCHAR(500) NOT NULL
);

-- ───────────────────────────────────────────────────────────────────
-- status_history — one audit row per state transition
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE status_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL REFERENCES applications (id),
    from_status    VARCHAR(30),                    -- NULL on creation
    to_status      VARCHAR(30) NOT NULL,
    actor_id       BIGINT      REFERENCES users (id),  -- NULL = system (e.g. deadline job)
    justification  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_history_app
    ON status_history (application_id, created_at);

-- ───────────────────────────────────────────────────────────────────
-- email_outbox — transactional decoupling from SMTP
-- ───────────────────────────────────────────────────────────────────
CREATE TABLE email_outbox (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient  VARCHAR(255) NOT NULL,
    template   VARCHAR(60)  NOT NULL,
    payload    JSONB        NOT NULL DEFAULT '{}',
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts   INT          NOT NULL DEFAULT 0,
    last_error TEXT,
    sent_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- retry job scans only what is pending
CREATE INDEX idx_email_outbox_pending
    ON email_outbox (created_at)
    WHERE status = 'PENDING';
