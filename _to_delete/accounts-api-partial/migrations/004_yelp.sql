-- Yelp content lives in its own tables, never in `reviews`.
--
-- Two reasons, and both are load-bearing. Yelp is licensed rather than open, so its ratings must
-- not be folded into the average we compute from our own diners and Mangrove — that average is a
-- claim about our data. And its terms cap how long a client may retain the content, which a table
-- we prune on a TTL can honour and a permanent review row cannot.

CREATE TABLE IF NOT EXISTS yelp_places (
  place_id     TEXT PRIMARY KEY,
  yelp_id      TEXT,
  name         TEXT NOT NULL DEFAULT '',
  rating       NUMERIC(2,1) NOT NULL DEFAULT 0,
  review_count INTEGER NOT NULL DEFAULT 0,
  price        TEXT NOT NULL DEFAULT '',
  url          TEXT NOT NULL DEFAULT '',
  image_url    TEXT NOT NULL DEFAULT '',
  categories   TEXT NOT NULL DEFAULT '',
  -- false records a searched-but-unmatched place, so a miss is cached like a hit.
  matched      BOOLEAN NOT NULL DEFAULT false,
  synced_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS yelp_places_synced_idx ON yelp_places (synced_at);

CREATE TABLE IF NOT EXISTS yelp_reviews (
  place_id   TEXT NOT NULL,
  review_id  TEXT NOT NULL,
  rating     INTEGER NOT NULL DEFAULT 0,
  text       TEXT NOT NULL DEFAULT '',
  author     TEXT NOT NULL DEFAULT '',
  -- Every excerpt links back to the full review on Yelp; the UI is not allowed to render one
  -- without this, so it is NOT NULL.
  url        TEXT NOT NULL,
  created_at TIMESTAMPTZ,
  synced_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (place_id, review_id)
);

CREATE INDEX IF NOT EXISTS yelp_reviews_place_idx ON yelp_reviews (place_id, synced_at);
