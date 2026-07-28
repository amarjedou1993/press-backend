package com.presscard.press_accreditation.email;

/**
 * The message catalogue. Subject and body live HERE, beside each other, in
 * French — so the wording of an official notification is reviewable in one
 * file rather than scattered across the services that trigger it.
 *
 * Bodies are plain text with {placeholders}. Plain text is deliberate: it
 * renders everywhere, survives every mail client, is never marked as spam for
 * its markup, and cannot hide a misleading link behind friendly text.
 */
public enum EmailTemplate {

    APPLICATION_ACCEPTED(
            "HAPA — Votre demande de carte de presse a été acceptée",
            """
            Bonjour {fullName},

            La commission d'examen a accepté votre demande de carte de presse
            (dossier n° {applicationId}).

            {message}

            Votre carte sera éditée par la HAPA et vous serez informé dès
            qu'elle sera disponible.

            Suivre votre dossier : {link}

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    APPLICATION_REJECTED(
            "HAPA — Décision concernant votre demande de carte de presse",
            """
            Bonjour {fullName},

            Après examen, la commission n'a pas pu donner une suite favorable
            à votre demande de carte de presse (dossier n° {applicationId}).

            Motif de la décision :
            {message}

            VOUS POUVEZ CONTESTER CETTE DÉCISION. Une réclamation peut être
            déposée depuis votre espace candidat pendant la phase de
            réclamation de la session. Elle sera examinée par un autre membre
            de la commission que celui qui a rendu la présente décision.

            Consulter votre dossier : {link}

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    CORRECTION_REQUESTED(
            "HAPA — Des corrections sont demandées sur votre dossier",
            """
            Bonjour {fullName},

            La commission d'examen a examiné votre dossier n° {applicationId}
            et demande des corrections avant de pouvoir se prononcer.

            Ce qui doit être corrigé :
            {message}

            Le détail pièce par pièce figure dans votre espace candidat : les
            documents concernés y sont signalés, avec l'observation de la
            commission.

            IMPORTANT : les corrections doivent être déposées avant la fin de
            la phase de correction. Passé ce délai, et sans réponse de votre
            part, le dossier sera rejeté.

            Corriger mon dossier : {link}

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    VERIFY_EMAIL(
            "HAPA — Vérifiez votre adresse e-mail",
            """
            Bonjour {fullName},

            Votre compte a bien été créé sur la plateforme d'accréditation
            presse de la HAPA.

            Pour pouvoir déposer une demande de carte de presse, veuillez
            confirmer votre adresse e-mail en ouvrant ce lien :

            {link}

            Ce lien est valable {hours} heures.

            Vous pouvez dès à présent vous connecter et compléter votre profil ;
            seule la soumission d'un dossier nécessite cette vérification.

            Si vous n'êtes pas à l'origine de cette inscription, ignorez ce
            message.

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    PASSWORD_RESET(
            "HAPA — Réinitialisation de votre mot de passe",
            """
            Bonjour {fullName},

            Une réinitialisation de mot de passe a été demandée pour votre
            compte. Pour choisir un nouveau mot de passe, ouvrez ce lien :

            {link}

            Ce lien est valable {minutes} minutes et ne peut être utilisé
            qu'une seule fois.

            Si vous n'avez pas fait cette demande, aucune action n'est
            nécessaire : votre mot de passe actuel reste valable.

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    EMAIL_CHANGE(
            "HAPA — Confirmez votre nouvelle adresse e-mail",
            """
            Bonjour {fullName},

            Vous avez demandé à utiliser cette adresse pour votre compte
            d'accréditation presse. Pour confirmer, ouvrez ce lien :

            {link}

            Ce lien est valable {hours} heures.

            Tant que la confirmation n'a pas eu lieu, votre ancienne adresse
            reste active.

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    EMAIL_CHANGE_NOTICE(
            "HAPA — Demande de changement d'adresse e-mail",
            """
            Bonjour {fullName},

            Une demande de changement d'adresse e-mail a été enregistrée sur
            votre compte, vers : {newEmail}

            Si vous êtes à l'origine de cette demande, aucune action n'est
            nécessaire : confirmez-la depuis la nouvelle adresse.

            SI CE N'EST PAS VOUS, changez immédiatement votre mot de passe et
            contactez la HAPA.

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """),

    APPLICATION_SUBMITTED(
            "HAPA — Votre dossier a bien été reçu",
            """
            Bonjour {fullName},

            Votre demande de carte de presse (dossier n° {applicationId}) a
            été reçue et transmise à la commission d'examen.

            Vous serez informé par e-mail de toute évolution : demande de
            correction, décision, ou édition de votre carte.

            Suivre votre dossier : {link}

            --
            Haute Autorité de la Presse et de l'Audiovisuel
            République Islamique de Mauritanie
            """);



    private final String subject;
    private final String body;

    EmailTemplate(String subject, String body) {
        this.subject = subject;
        this.body = body;
    }

    public String subject() {
        return subject;
    }

    /** Substitute {placeholders} from the payload. */
    public String render(java.util.Map<String, Object> payload) {
        String result = body;
        for (var entry : payload.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}",
                    String.valueOf(entry.getValue()));
        }
        return result;
    }
}
