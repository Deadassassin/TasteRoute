import sys, os
ROOT = os.path.expanduser("~/mnt/TasteRoute/app/src/main/java/space/gexemy/tasteroute")
edits = []
def E(p, old, new, n=1): edits.append((p, old, new, n))

P = "ui/detail/PlaceDetailScreen.kt"
STAR_TEXT = '''                        Text(
                            "★".repeat(review.rating.coerceIn(1, 5)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )'''
STAR_TEXT2 = '''                    Text(
                        "★".repeat(review.rating.coerceIn(1, 5)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )'''
E(P, STAR_TEXT,  '''                        StarRow(review.rating)''')
E(P, STAR_TEXT2, '''                    StarRow(review.rating)''')

# The shared row, appended near the other private helpers.
E(P, '''@Composable
private fun Section(title: String, content: @Composable () -> Unit) {''',
'''/**
 * A review's rating, drawn with the same star the rest of the app uses.
 *
 * It was `"★".repeat(n)` — the typographic star, set in the body face. It inherited whatever
 * weight and metrics the user's chosen font happens to give U+2605 (which for a downloadable font
 * is often nothing, so it fell back to a different face mid-line), it sat on the text baseline
 * instead of centred, and it was a different shape from the `Icons.Filled.Star` on every card in
 * the feed. Two kinds of star in one app is the sort of thing nobody names but everybody sees.
 */
@Composable
private fun StarRow(rating: Int) {
    val filled = rating.coerceIn(1, 5)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(filled) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {''')

# Profile's joined metadata line: same glyph, no room for an icon.
E("ui/profile/ProfileScreen.kt",
  '''                    visit.rating?.let { add("$it★") }''',
  '''                    visit.rating?.let { add("Rated $it") }''')

# A user cannot set a Gradle property.
E("ui/account/AccountScreen.kt",
'''                    "This build has no server configured — set GEXEMY_BASE_URL in local.properties.",''',
'''                    // Was: "set GEXEMY_BASE_URL in local.properties". Nobody holding a phone can
                    // do that, and telling them to is how an app admits it was never finished.
                    "Accounts aren't available in this build. Everything else works without one.",''')

def main():
    bufs, problems = {}, []
    for path, old, new, n in edits:
        if path not in bufs:
            bufs[path] = open(os.path.join(ROOT, path), encoding="utf-8").read()
        found = bufs[path].count(old)
        if found != n:
            problems.append("%s: expected %d found %d :: %s" % (path, n, found, old.strip().splitlines()[0][:80]))
            continue
        bufs[path] = bufs[path].replace(old, new, n)
    if problems:
        print("PHASE 1 FAILED - nothing written")
        for p in problems: print(" -", p)
        sys.exit(1)
    for path, content in bufs.items():
        with open(os.path.join(ROOT, path), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print("wrote", path)
main()
