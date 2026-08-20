//package com.presscard.press_accreditation.email;
//
///**
// * The message catalogue. Subject and body live HERE, beside each other, in
// * French — so the wording of an official notification is reviewable in one
// * file rather than scattered across the services that trigger it.
// *
// * Bodies are plain text with {placeholders}. Plain text is deliberate: it
// * renders everywhere, survives every mail client, is never marked as spam for
// * its markup, and cannot hide a misleading link behind friendly text.
// */
//public enum EmailTemplate {
//
//    APPLICATION_ACCEPTED(
//            "MCACRP — Votre demande de carte de presse a été acceptée",
//            """
//            Bonjour {fullName},
//
//            La commission d'examen a accepté votre demande de carte de presse
//            (dossier n° {applicationId}).
//
//            {message}
//
//            Votre carte sera éditée par le MCACRP et vous serez informé dès
//            qu'elle sera disponible.
//
//            Suivre votre dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CORRECTION_DEADLINE_WARNING(
//            "MCACRP — Vos corrections sont attendues sous {days} jours",
//            """
//            Bonjour {fullName},
//
//            La commission d'examen a demandé des corrections sur votre dossier
//            n° {applicationId}. À ce jour, elles n'ont pas été déposées.
//
//            DATE LIMITE : {deadline}
//
//            Passé ce délai, votre dossier sera rejeté pour dossier incomplet,
//            sans nouvel examen. Vous conserverez alors votre droit de
//            réclamation.
//
//            Déposer mes corrections : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CORRECTION_RESUBMITTED(
//            "MCACRP — Vos corrections ont bien été reçues",
//            """
//            Bonjour {fullName},
//
//            Les corrections demandées sur votre dossier n° {applicationId} ont
//            été reçues. Votre dossier retourne devant la commission pour
//            examen final.
//
//            Vous serez informé par e-mail de la décision.
//
//            Suivre mon dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    APPLICATION_REJECTED(
//            "MCACRP — Décision concernant votre demande de carte de presse",
//            """
//            Bonjour {fullName},
//
//            Après examen, la commission n'a pas pu donner une suite favorable
//            à votre demande de carte de presse (dossier n° {applicationId}).
//
//            Motif de la décision :
//            {message}
//
//            VOUS POUVEZ CONTESTER CETTE DÉCISION. Une réclamation peut être
//            déposée depuis votre espace candidat pendant la phase de
//            réclamation de la session. Elle sera examinée par un autre membre
//            de la commission que celui qui a rendu la présente décision.
//
//            Consulter votre dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CORRECTION_REQUESTED(
//            "MCACRP — Des corrections sont demandées sur votre dossier",
//            """
//            Bonjour {fullName},
//
//            La commission d'examen a examiné votre dossier n° {applicationId}
//            et demande des corrections avant de pouvoir se prononcer.
//
//            Ce qui doit être corrigé :
//            {message}
//
//            Le détail pièce par pièce figure dans votre espace candidat : les
//            documents concernés y sont signalés, avec l'observation de la
//            commission.
//
//            IMPORTANT : les corrections doivent être déposées avant la fin de
//            la phase de correction. Passé ce délai, et sans réponse de votre
//            part, le dossier sera rejeté.
//
//            Corriger mon dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    OBJECTION_RECEIVED(
//            "MCACRP — Votre réclamation a bien été enregistrée",
//            """
//            Bonjour {fullName},
//
//            Votre réclamation concernant le dossier n° {applicationId} a été
//            enregistrée.
//
//            Motif invoqué : {reason}
//
//            Elle sera examinée par un membre de la commission DIFFÉRENT de
//            celui ayant rendu la décision contestée. Vous serez informé par
//            e-mail de la décision définitive.
//
//            Suivre mon dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CARD_ISSUED(
//            "MCACRP — Votre carte de presse a été éditée",
//            """
//            Bonjour {fullName},
//
//            Votre carte de presse a été établie par la Haute Autorité.
//
//            Numéro de carte : {cardNumber}
//            Valable jusqu'au : {expiresAt}
//
//            Votre carte porte au dos un code QR permettant à toute personne de
//            vérifier sa validité auprès de la MCACRP.
//
//            Les modalités de retrait vous seront communiquées prochainement.
//
//            Consulter mon dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    VERIFY_EMAIL(
//            "MCACRP — Vérifiez votre adresse e-mail",
//            """
//            Bonjour {fullName},
//
//            Votre compte a bien été créé sur la plateforme d'accréditation
//            presse de la MCACRP.
//
//            Pour pouvoir déposer une demande de carte de presse, veuillez
//            confirmer votre adresse e-mail en ouvrant ce lien :
//
//            {link}
//
//            Ce lien est valable {hours} heures.
//
//            Vous pouvez dès à présent vous connecter et compléter votre profil ;
//            seule la soumission d'un dossier nécessite cette vérification.
//
//            Si vous n'êtes pas à l'origine de cette inscription, ignorez ce
//            message.
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    PASSWORD_RESET(
//            "MCACRP — Réinitialisation de votre mot de passe",
//            """
//            Bonjour {fullName},
//
//            Une réinitialisation de mot de passe a été demandée pour votre
//            compte. Pour choisir un nouveau mot de passe, ouvrez ce lien :
//
//            {link}
//
//            Ce lien est valable {minutes} minutes et ne peut être utilisé
//            qu'une seule fois.
//
//            Si vous n'avez pas fait cette demande, aucune action n'est
//            nécessaire : votre mot de passe actuel reste valable.
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    EMAIL_CHANGE(
//            "MCACRP — Confirmez votre nouvelle adresse e-mail",
//            """
//            Bonjour {fullName},
//
//            Vous avez demandé à utiliser cette adresse pour votre compte
//            d'accréditation presse. Pour confirmer, ouvrez ce lien :
//
//            {link}
//
//            Ce lien est valable {hours} heures.
//
//            Tant que la confirmation n'a pas eu lieu, votre ancienne adresse
//            reste active.
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    EMAIL_CHANGE_NOTICE(
//            "MCACRP — Demande de changement d'adresse e-mail",
//            """
//            Bonjour {fullName},
//
//            Une demande de changement d'adresse e-mail a été enregistrée sur
//            votre compte, vers : {newEmail}
//
//            Si vous êtes à l'origine de cette demande, aucune action n'est
//            nécessaire : confirmez-la depuis la nouvelle adresse.
//
//            SI CE N'EST PAS VOUS, changez immédiatement votre mot de passe et
//            contactez la MCACRP.
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    APPLICATION_SUBMITTED(
//            "MCACRP — Votre dossier a bien été reçu",
//            """
//            Bonjour {fullName},
//
//            Votre demande de carte de presse (dossier n° {applicationId}) a
//            été reçue et transmise à la commission d'examen.
//
//            Vous serez informé par e-mail de toute évolution : demande de
//            correction, décision, ou édition de votre carte.
//
//            Suivre votre dossier : {link}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CARD_SUSPENDED(
//            "HAPA — Votre carte de presse est suspendue",
//                    """
//            Bonjour {fullName},
//
//            Votre carte de presse n° {cardNumber} est SUSPENDUE à compter de ce
//            jour. Elle apparaîtra comme telle à toute vérification.
//
//            Motif :
//            {reason}
//
//            Une suspension est une mesure conservatoire et réversible. Pour
//            toute contestation, adressez-vous à la HAPA.
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CARD_REVOKED(
//            "HAPA — Retrait de votre carte de presse",
//                    """
//            Bonjour {fullName},
//
//            Votre carte de presse n° {cardNumber} a été RETIRÉE. Elle n'est
//            plus valable et apparaîtra comme retirée à toute vérification.
//
//            {reason}
//
//            Cette décision met fin à votre accréditation pour la présente
//            session. Vous pourrez déposer une nouvelle candidature lors d'une
//            prochaine session de candidature.
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    CARD_REINSTATED(
//            "HAPA — Votre carte de presse est rétablie",
//                    """
//            Bonjour {fullName},
//
//            Votre carte de presse n° {cardNumber} est de nouveau VALIDE.
//
//            Motif :
//            {reason}
//
//            --
//            Haute Autorité de la Presse et de l'Audiovisuel
//            République Islamique de Mauritanie
//            """),
//
//    REVOCATION_PROPOSED(
//            "HAPA — Proposition de retrait de carte n° {proposalId}",
//                    """
//            Une proposition de retrait a été déposée par un membre de la
//            commission d'examen.
//
//            Carte : {cardNumber}
//            Motif invoqué : {ground}
//
//            Le retrait d'une carte exige la décision de la Haute Autorité.
//            Examiner la proposition : {link}
//
//            --
//            Système d'accréditation HAPA
//            """);
//
//
//
//
//    private final String subject;
//    private final String body;
//
//    EmailTemplate(String subject, String body) {
//        this.subject = subject;
//        this.body = body;
//    }
//
//    public String subject() {
//        return subject;
//    }
//
//    /** Substitute {placeholders} from the payload. */
//    public String render(java.util.Map<String, Object> payload) {
//        String result = body;
//        for (var entry : payload.entrySet()) {
//            result = result.replace("{" + entry.getKey() + "}",
//                    String.valueOf(entry.getValue()));
//        }
//        return result;
//    }
//}


package com.presscard.press_accreditation.email;

/**
 * Which message, not what it says.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE TEXT LEFT THIS FILE, AND THAT IS THE POINT.
 *
 * Fifteen templates in French were four hundred lines of Java string
 * literals. In two languages they would be nine hundred — and nobody who
 * revises official wording can work in a .java file. A ministry's
 * communications officer should be able to correct a sentence without a
 * compiler.
 *
 * The bodies now live in `messages/email_fr.properties` and
 * `messages/email_ar.properties`, keyed by the enum constant. Adding a
 * language means adding a file; changing a sentence means changing a line.
 *
 * What remains here is the CATALOGUE: which messages exist, and whether each
 * carries a call to action.
 * ───────────────────────────────────────────────────────────────────────
 */
public enum EmailTemplate {

    /* ── account lifecycle ── */
    VERIFY_EMAIL(true),
    PASSWORD_RESET(true),
    EMAIL_CHANGE(true),
    /** Sent to the OLD address as a warning — deliberately no link. */
    EMAIL_CHANGE_NOTICE(false),

    /* ── application lifecycle ── */
    APPLICATION_SUBMITTED(true),
    CORRECTION_REQUESTED(true),
    CORRECTION_DEADLINE_WARNING(true),
    CORRECTION_RESUBMITTED(true),
    APPLICATION_ACCEPTED(true),
    APPLICATION_REJECTED(true),
    OBJECTION_RECEIVED(true),
    CARD_ISSUED(true),

    /* ── the card in circulation ── */
    CARD_SUSPENDED(false),
    CARD_REVOKED(false),
    CARD_REINSTATED(false),

    /* ── staff ── */
    REVOCATION_PROPOSED(true);

    private final boolean hasAction;

    EmailTemplate(boolean hasAction) {
        this.hasAction = hasAction;
    }

    /**
     * Whether the message ends with a button.
     *
     * ⚠️ EMAIL_CHANGE_NOTICE and the three card notices carry NONE, and that
     * is a decision rather than an omission: a warning sent to an address
     * that may have been compromised must not contain a link, and a
     * suspension notice should not look like something to click through.
     */
    public boolean hasAction() {
        return hasAction;
    }

    /** Message-bundle keys, derived so they cannot drift from the constant. */
    public String subjectKey() { return name() + ".subject"; }
    public String bodyKey()    { return name() + ".body"; }
    public String actionKey()  { return name() + ".action"; }
}
