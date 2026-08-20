-- -- V<N>__seed_objection_reasons.sql
-- --
-- -- The grounds on which a refused candidate may contest.
-- --
-- -- ───────────────────────────────────────────────────────────────────────
-- -- ⚠️ THIS LIST IS A PROPOSAL, NOT A DECISION.
-- --
-- -- The grounds of appeal against an administrative act are a LEGAL matter and
-- -- they belong to the Ministry. What follows is a complete, defensible set
-- -- drawn from how the workflow can actually go wrong — but it must be put in
-- -- front of HAPA before the dry-run, and their wording wins.
-- --
-- -- ⚠️ AND THE ARABIC IS MINE, NOT A LAWYER'S. These sentences will be read at
-- -- the moment a journalist contests a refusal. They need a native reading
-- -- before deployment — the French is the reference text and the Arabic must
-- -- say the same thing, not merely something similar.
-- --
-- -- THE SHAPE OF THE LIST. Six specific grounds plus OTHER. Each names a
-- -- DIFFERENT way the decision could be wrong, so a candidate choosing one
-- -- tells the second reviewer where to look. A list of vague grounds would
-- -- collect every objection under "other" and help nobody.
-- -- ───────────────────────────────────────────────────────────────────────
--
-- INSERT INTO objection_reasons (code, label_fr, label_ar, hint_fr, hint_ar, display_order, active)
-- VALUES
--
-- -- 1 ─ The commonest real case: something was filed and not looked at.
-- ('PIECE_OVERLOOKED',
--  'Une pièce fournie n''a pas été prise en compte',
--  'لم تؤخذ إحدى الوثائق المقدمة بعين الاعتبار',
--  'Choisissez ce motif si votre dossier contenait un document que la décision ne mentionne pas, ou qu''elle décrit comme manquant. Indiquez lequel.',
--  'اختر هذا المبرر إذا كان ملفك يتضمن وثيقة لا يذكرها القرار، أو يعتبرها ناقصة. وحدد أي وثيقة.',
--  1, true),
--
-- -- 2 ─ The documents were seen but read wrongly.
-- ('FACTUAL_ERROR',
--  'La décision repose sur une appréciation erronée de mon activité',
--  'يستند القرار إلى تقدير خاطئ لنشاطي',
--  'Choisissez ce motif si la décision affirme quelque chose d''inexact sur votre exercice — période, régularité, employeur. Précisez ce qui est inexact et ce que vos pièces établissent.',
--  'اختر هذا المبرر إذا أكد القرار أمرًا غير صحيح بشأن ممارستك — المدة أو الانتظام أو المشغّل. وبيّن ما هو غير صحيح وما تثبته وثائقك.',
--  2, true),
--
-- -- 3 ─ Judged against the wrong set of requirements.
-- ('WRONG_CATEGORY',
--  'La décision applique une catégorie qui n''est pas la mienne',
--  'يطبق القرار فئة لا تنطبق عليّ',
--  'Choisissez ce motif si votre dossier a été examiné au regard d''exigences qui ne correspondent pas à la catégorie sous laquelle vous exercez.',
--  'اختر هذا المبرر إذا دُرس ملفك في ضوء شروط لا تطابق الفئة التي تمارس ضمنها.',
--  3, true),
--
-- -- 4 ─ The reason given does not permit an answer.
-- ('INSUFFICIENT_GROUNDS',
--  'Le motif communiqué ne permet pas de comprendre le refus',
--  'المبرر المبلَّغ لا يسمح بفهم الرفض',
--  'Choisissez ce motif si la décision ne dit pas assez pour que vous puissiez y répondre, ou si elle invoque une exigence qui ne vous avait pas été indiquée.',
--  'اختر هذا المبرر إذا لم يقل القرار ما يكفي لتتمكن من الرد عليه، أو إذا استند إلى شرط لم يُبلَّغ لك.',
--  4, true),
--
-- -- 5 ─ Something now exists that did not exist at submission.
-- ('NEW_EVIDENCE',
--  'Je peux désormais produire une pièce qui faisait défaut',
--  'يمكنني الآن تقديم وثيقة كانت ناقصة',
--  'Choisissez ce motif si vous disposez aujourd''hui d''un document que vous ne pouviez pas fournir dans les délais. Décrivez-le ; la commission vous indiquera comment le transmettre.',
--  'اختر هذا المبرر إذا أصبحت تتوفر اليوم على وثيقة لم تكن تستطيع تقديمها في الآجال. صفها، وستبين لك اللجنة كيفية إرسالها.',
--  5, true),
--
-- -- 6 ─ The process itself, rather than its conclusion.
-- ('PROCEDURAL',
--  'La procédure n''a pas été respectée',
--  'لم تُحترم الإجراءات',
--  'Choisissez ce motif si vous n''avez pas reçu la demande de correction annoncée, si un délai n''a pas été tenu, ou si votre dossier a été traité hors des règles publiées.',
--  'اختر هذا المبرر إذا لم يصلك طلب التصحيح المعلن، أو لم يُحترم أجل من الآجال، أو عولج ملفك خارج القواعد المنشورة.',
--  6, true),
--
-- -- 7 ─ The escape hatch. NO HINT: the argument does the work, and a hint here
-- --     would only invite people to use it instead of a precise ground.
-- ('OTHER',
--  'Autre motif',
--  'مبرر آخر',
--  NULL,
--  NULL,
--  7, true);


-- V<N>__email_outbox_locale.sql
--
-- Which language a queued message is written in.
--
-- ⚠️ THE LOCALE IS RESOLVED WHEN THE MESSAGE IS QUEUED, NOT WHEN IT IS SENT.
--
-- The outbox is drained by a scheduled job, possibly minutes or hours later,
-- and possibly after the holder has changed their preference. The language a
-- message is written in should be the one in force WHEN THE EVENT HAPPENED —
-- otherwise a decision taken while someone read French could arrive in Arabic
-- because they switched in the meantime, and a retry could differ from the
-- first attempt.
--
-- DEFAULT 'fr': existing rows are staff notifications and pre-bilingual
-- candidate mail, all of which were composed in French.

ALTER TABLE email_outbox
    ADD COLUMN locale VARCHAR(2) NOT NULL DEFAULT 'fr';

ALTER TABLE email_outbox
    ADD CONSTRAINT email_outbox_locale_valid
        CHECK (locale IN ('ar', 'fr'));

COMMENT ON COLUMN email_outbox.locale IS
    'ISO 639-1 code the message body is rendered in. Fixed at queue time from '
    'the recipient''s preferred_locale, so a retry reproduces the first '
    'attempt and a later preference change does not rewrite history.';
