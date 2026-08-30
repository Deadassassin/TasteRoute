import { clientIp, num, str, strList } from '../http.js';
import { limit } from '../ratelimit.js';
import * as yelp from '../sources/yelp.js';

/**
 * Coordinates and name come from the caller: this API keeps no place table, and the client already
 * holds both. Without them a cold place returns whatever is cached and nothing more.
 */
export async function getYelp(req, body, user, params) {
  limit(`yelp:${clientIp(req)}`, 90, 15 * 60_000);
  const placeId = str(params.placeId, 120);
  const lat = num(body.lat), lng = num(body.lng);
  const name = str(body.name, 200);
  const coords = lat !== null && lng !== null ? { lat, lng } : null;

  const info = await yelp.info(placeId, coords, name).catch(() => null);
  return { place_id: placeId, enabled: yelp.enabled(), yelp: info, attribution: yelp.attribution };
}

export async function batchYelp(req, body) {
  limit(`yelpb:${clientIp(req)}`, 45, 15 * 60_000);
  const raw = Array.isArray(body.places) ? body.places.slice(0, 60) : [];
  const places = raw
    .map((p) => ({ id: str(p?.id, 120), lat: num(p?.lat), lng: num(p?.lng), name: str(p?.name, 200) }))
    .filter((p) => p.id && p.lat !== null && p.lng !== null);

  // Ids with no coordinates still get whatever is already cached.
  const idsOnly = strList(body.place_ids, 60, 120).map((id) => ({ id, lat: 0, lng: 0, name: '' }));
  const merged = [...places, ...idsOnly.filter((p) => !places.some((q) => q.id === p.id))];

  return { places: await yelp.batch(merged).catch(() => ({})), attribution: yelp.attribution };
}
