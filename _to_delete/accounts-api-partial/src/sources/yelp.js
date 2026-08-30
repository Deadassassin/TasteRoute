import { config } from '../config.js';
import { all, one, query } from '../db.js';

/**
 * Yelp Fusion as a rating + review-excerpt source, keyed to our OSM place ids.
 *
 * Yelp is a licensed source, not an open one, so three rules are structural here and must not be
 * relaxed: its content is stored apart from the `reviews` table (never commingled into our own
 * average), every cached row carries `url` so the UI can link back as Yelp's display requirements
 * demand, and nothing is kept past `YELP_TTL_HOURS`. The key lives on the server so no app release
 * ever ships it and the per-place cache is shared across every device.
 *
 * Matching is by /businesses/search rather than /businesses/matches: OSM addresses are frequently
 * missing the city/state that matches requires, and a name+coordinate search degrades gracefully
 * where matches simply 400s.
 */

const NAME = 'yelp';
const API = 'https://api.yelp.com/v3';
const UA = 'GexemyAPI/1.0 (+https://gexemy.space)';
const MATCH_METRES = 150;
const MATCH_SCORE = 0.5;
const REVIEW_LIMIT = 3;

export const attribution = { source: NAME, label: 'Yelp', url: 'https://www.yelp.com' };
export const sourceName = NAME;
export const enabled = () => config.yelpEnabled && config.yelpKey.length > 0;

/** Set when Yelp answers 429 so a blown daily quota costs one call, not one per request. */
let quotaTrippedUntil = 0;

const rad = (d) => (d * Math.PI) / 180;

function metresBetween(a, b) {
  const R = 6_371_000;
  const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

const norm = (s) => String(s ?? '')
  .toLowerCase()
  .normalize('NFD')
  .replace(/[\u0300-\u036f]/g, '')
  .replace(/[^a-z0-9]+/g, ' ')
  .trim();

/** Token overlap, so "Joe's Pizza Co" and "Joes Pizza" match but "Joe's Pizza" and "Joe's Tacos" don't. */
function nameScore(a, b) {
  const x = new Set(norm(a).split(' ').filter(Boolean));
  const y = new Set(norm(b).split(' ').filter(Boolean));
  if (!x.size || !y.size) return 0;
  let shared = 0;
  for (const t of x) if (y.has(t)) shared += 1;
  return shared / Math.min(x.size, y.size);
}

async function callYelp(path, params, timeoutMs = 4_000) {
  if (!enabled() || Date.now() < quotaTrippedUntil) return null;
  const url = new URL(`${API}${path}`);
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== '') url.searchParams.set(k, String(v));
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      signal: controller.signal,
      headers: { Authorization: `Bearer ${config.yelpKey}`, Accept: 'application/json', 'User-Agent': UA },
    });
    if (res.status === 429 || res.status === 402) {
      quotaTrippedUntil = Date.now() + 15 * 60_000;
      return null;
    }
    if (!res.ok) return null;
    return await res.json();
  } catch {
    // Yelp being slow or down must never fail the screen that asked for a restaurant.
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function findBusiness(coords, name) {
  const json = await callYelp('/businesses/search', {
    term: name,
    latitude: coords.lat.toFixed(6),
    longitude: coords.lng.toFixed(6),
    radius: Math.round(MATCH_METRES * 3),
    limit: 8,
    sort_by: 'distance',
  });
  const candidates = Array.isArray(json?.businesses) ? json.businesses : [];

  let best = null, bestScore = 0;
  for (const b of candidates) {
    const at = { lat: b.coordinates?.latitude, lng: b.coordinates?.longitude };
    if (at.lat == null || at.lng == null) continue;
    if (metresBetween(coords, at) > MATCH_METRES) continue;
    const score = nameScore(name, b.name);
    const contains = norm(name).includes(norm(b.name)) || norm(b.name).includes(norm(name));
    if ((score >= MATCH_SCORE || contains) && score >= bestScore) {
      best = b;
      bestScore = score;
    }
  }
  return best;
}

async function fetchReviews(yelpId) {
  const json = await callYelp(`/businesses/${encodeURIComponent(yelpId)}/reviews`, {
    limit: REVIEW_LIMIT,
    sort_by: 'yelp_sort',
  });
  return (Array.isArray(json?.reviews) ? json.reviews : []).map((r) => ({
    reviewId: String(r.id ?? '').slice(0, 120),
    rating: Math.min(5, Math.max(1, Math.round(Number(r.rating) || 0))),
    text: String(r.text ?? '').slice(0, 400),
    author: String(r.user?.name ?? '').slice(0, 60) || 'Yelp reviewer',
    url: String(r.url ?? ''),
    createdAt: r.time_created ? new Date(String(r.time_created).replace(' ', 'T') + 'Z') : null,
  })).filter((r) => r.reviewId && r.url);
}

const ttlInterval = () => `${Math.max(1, config.yelpTtlHours)} hours`;

async function readCached(placeId) {
  const row = await one(
    `SELECT place_id, yelp_id, name, rating::float AS rating, review_count, price, url, image_url,
            categories, matched, synced_at > now() - ($2)::interval AS fresh
     FROM yelp_places WHERE place_id = $1`,
    [placeId, ttlInterval()],
  );
  return row ?? null;
}

async function readReviews(placeId) {
  const rows = await all(
    `SELECT rating, text, author, url, created_at FROM yelp_reviews
     WHERE place_id = $1 AND synced_at > now() - ($2)::interval
     ORDER BY created_at DESC NULLS LAST LIMIT $3`,
    [placeId, ttlInterval(), REVIEW_LIMIT],
  );
  return rows.map((r) => ({
    rating: r.rating,
    text: r.text,
    author: r.author,
    url: r.url,
    created_at: r.created_at,
  }));
}

const shape = (row, reviews = []) => (row?.matched ? {
  rating: Number(row.rating) || 0,
  review_count: row.review_count ?? 0,
  price: row.price ?? '',
  url: row.url ?? '',
  image_url: row.image_url || null,
  categories: String(row.categories ?? '').split('|').filter(Boolean),
  reviews,
} : null);

/**
 * Refresh one place from Yelp. Bounded by the cache row, so calling this on every place view costs
 * at most one upstream call per place per TTL window.
 */
async function sync(placeId, coords, name) {
  const business = await findBusiness(coords, name);

  if (!business) {
    // Record the miss too: an unmatched place must not be re-searched on every view.
    await query(
      `INSERT INTO yelp_places (place_id, matched, synced_at) VALUES ($1, false, now())
       ON CONFLICT (place_id) DO UPDATE SET matched = false, synced_at = now()`,
      [placeId],
    );
    return null;
  }

  const categories = (business.categories ?? []).map((c) => c.title).filter(Boolean).slice(0, 4).join('|');
  await query(
    `INSERT INTO yelp_places
       (place_id, yelp_id, name, rating, review_count, price, url, image_url, categories, matched, synced_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, true, now())
     ON CONFLICT (place_id) DO UPDATE SET
       yelp_id = EXCLUDED.yelp_id, name = EXCLUDED.name, rating = EXCLUDED.rating,
       review_count = EXCLUDED.review_count, price = EXCLUDED.price, url = EXCLUDED.url,
       image_url = EXCLUDED.image_url, categories = EXCLUDED.categories,
       matched = true, synced_at = now()`,
    [
      placeId, String(business.id ?? '').slice(0, 120), String(business.name ?? '').slice(0, 200),
      Number(business.rating) || 0, Number(business.review_count) || 0,
      String(business.price ?? '').slice(0, 8), String(business.url ?? '').slice(0, 500),
      String(business.image_url ?? '').slice(0, 500), categories,
    ],
  );

  const reviews = await fetchReviews(business.id);
  // Licensed content: drop the previous window rather than accumulating it.
  await query(`DELETE FROM yelp_reviews WHERE place_id = $1`, [placeId]);
  for (const r of reviews) {
    await query(
      `INSERT INTO yelp_reviews (place_id, review_id, rating, text, author, url, created_at, synced_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, now())
       ON CONFLICT (place_id, review_id) DO UPDATE SET
         rating = EXCLUDED.rating, text = EXCLUDED.text, author = EXCLUDED.author,
         url = EXCLUDED.url, created_at = EXCLUDED.created_at, synced_at = now()`,
      [placeId, r.reviewId, r.rating, r.text, r.author, r.url, r.createdAt],
    );
  }
  return true;
}

/** Everything Yelp has for one place, refreshing through the cache when stale. Null when unmatched. */
export async function info(placeId, coords, name) {
  if (!enabled()) return null;
  const cached = await readCached(placeId);
  if (cached?.fresh) return shape(cached, cached.matched ? await readReviews(placeId) : []);
  if (!coords || !name) return shape(cached, cached?.matched ? await readReviews(placeId) : []);

  await sync(placeId, coords, name).catch(() => null);
  const fresh = await readCached(placeId);
  return shape(fresh, fresh?.matched ? await readReviews(placeId) : []);
}

/**
 * Ratings for a screen full of cards. Cache hits are free; misses are looked up but capped per
 * request, because one Yelp call per visible card would burn a daily plan in a handful of searches.
 */
export async function batch(places) {
  if (!enabled() || !places.length) return {};
  const ids = places.map((p) => p.id);
  const rows = await all(
    `SELECT place_id, yelp_id, name, rating::float AS rating, review_count, price, url, image_url,
            categories, matched, synced_at > now() - ($2)::interval AS fresh
     FROM yelp_places WHERE place_id = ANY($1::text[])`,
    [ids, ttlInterval()],
  );
  const byId = new Map(rows.map((r) => [r.place_id, r]));

  // A lookup needs a name and a real fix; id-only entries can only ever be served from cache.
  const stale = places
    .filter((p) => !byId.get(p.id)?.fresh && p.name && p.lat && p.lng)
    .slice(0, config.yelpBatchLookups);
  if (stale.length) {
    await Promise.all(stale.map((p) => sync(p.id, { lat: p.lat, lng: p.lng }, p.name).catch(() => null)));
    const refreshed = await all(
      `SELECT place_id, yelp_id, name, rating::float AS rating, review_count, price, url, image_url,
              categories, matched
       FROM yelp_places WHERE place_id = ANY($1::text[])`,
      [stale.map((p) => p.id)],
    );
    for (const r of refreshed) byId.set(r.place_id, r);
  }

  const out = {};
  for (const [id, row] of byId) {
    const value = shape(row);
    if (value) out[id] = value;
  }
  return out;
}
