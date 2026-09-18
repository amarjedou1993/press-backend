-- ⚠️ QUAND CE CORPS A ÉTÉ PRÉVENU POUR LA DERNIÈRE FOIS.
--
-- Sans elle, le travail nocturne renvoie le même avis chaque nuit pendant
-- quatre-vingt-dix jours — et une institution qui reçoit quatre-vingt-dix
-- fois le même message cesse de lire les messages du Ministère.
--
-- Sur l'institution et non sur la carte : l'avis porte sur un ensemble
-- (« douze de vos cartes expirent »), et le marqueur appartient là où le
-- message appartient.
ALTER TABLE institutions
    ADD COLUMN IF NOT EXISTS renewal_notified_at TIMESTAMPTZ;

COMMENT ON COLUMN institutions.renewal_notified_at IS
    'Last time this body was told its cards are approaching expiry. NULL '
    'means never. Compared against a cooling-off period so one window '
    'produces one notice, not ninety.';