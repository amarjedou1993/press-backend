-- ═══════════════════════════════════════════════════════════════════
-- THE PRODUCER'S DAILY DIGEST — remembering what has been announced.
--
-- ⚠️ A TIMESTAMP, NOT A COUNT.
--
-- The obvious column was "how many were ready last time", compared against
-- how many are ready now. It is wrong on the ordinary day: three cards
-- produced and three granted leaves the total unchanged, no message is sent,
-- and three people wait for cards nobody knows are waiting.
--
-- The question a producer has is "is there anything I have not seen" — not
-- "has the number moved". So the mark is the moment of the last digest, and
-- the job asks whether anything was granted after it.
--
-- ⚠️ AND ONE TIMESTAMP COVERS THREE SERIES.
--
-- Cards, honour cards and institutional cards have three id sequences; a
-- watermark per series would be three columns and a rule about which one
-- moves. "Granted since I last wrote" needs none of that.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS print_digest_sent_at TIMESTAMPTZ;

COMMENT ON COLUMN users.print_digest_sent_at IS
    'PRINTER accounts only: when this producer was last told what is waiting. '
    'NULL means never, which the job treats as due — a producer account '
    'created today should hear about the queue that already exists.';
