import sys, os
ROOT = os.path.expanduser("~/mnt/TasteRoute/app/src/main/java/space/gexemy/tasteroute")
edits = []
def E(p, old, new, n=1): edits.append((p, old, new, n))
S = "ui/settings/SettingsScreen.kt"

E(S, '''import androidx.compose.foundation.clickable''',
     '''import android.util.Log
import androidx.compose.foundation.clickable''')

E(S, '''import space.gexemy.tasteroute.data.GexemyClient''',
     '''import space.gexemy.tasteroute.data.GexemyClient
import space.gexemy.tasteroute.data.LocationStatus''')

# The capability diff is a message to whoever runs the server. It goes to the log.
E(S, '''/**
 * A reachable server running last week's build fails in a way that looks nothing like a failure:
 * routes come back without turn directions and Yelp quietly returns nothing. Naming it is the
 * difference between a redeploy and an afternoon of debugging the app.
 */
private fun missingCapabilities(health: GexemyClient.Health): String? {
    if (!health.reportsCapabilities) {
        return "This build predates the capability list, so it has neither turn directions nor Yelp. " +
            "Redeploy gexemy-api.tar.gz on the server."
    }
    val missing = buildList {
        if (!health.supports("route-steps")) add("turn directions")
        if (!health.supports("yelp")) add("Yelp ratings")
        if (!health.supports("sources")) add("Google/Tripadvisor content")
        if (!health.supports("catalog")) add("the open place catalog")
        if (!health.supports("ai-models")) add("the live AI model list")
    }
    if (missing.isEmpty()) return null
    return "Server is behind this app: no ${missing.joinToString(" or ")}. Redeploy gexemy-api.tar.gz."
}''',
'''/**
 * A reachable server running last week's build fails in a way that looks nothing like a failure:
 * routes come back without turn directions and Yelp quietly returns nothing.
 *
 * TWO AUDIENCES, TWO STRINGS, and that separation is the point. The person holding the phone gets
 * the names of the features that are off, because that is the part they can see and act on — they
 * can stop waiting for a turn direction that is never coming. The instruction to redeploy a
 * tarball is addressed to whoever runs the server; it went to the screen for a year, and a
 * shipping app telling its user to redeploy anything is the clearest possible sign that nobody
 * separated the two. It goes to the log now.
 */
private fun missingCapabilities(health: GexemyClient.Health): List<String> {
    if (!health.reportsCapabilities) {
        Log.w(TAG, "Server predates the capability list — redeploy gexemy-api.tar.gz")
        return listOf("turn directions", "Yelp ratings")
    }
    val missing = buildList {
        if (!health.supports("route-steps")) add("turn directions")
        if (!health.supports("yelp")) add("Yelp ratings")
        if (!health.supports("sources")) add("Google and Tripadvisor ratings")
        if (!health.supports("catalog")) add("the wider place catalogue")
        if (!health.supports("ai-models")) add("the live model list")
    }
    if (missing.isNotEmpty()) {
        Log.w(TAG, "Server is behind this app: no ${missing.joinToString(", ")} — redeploy gexemy-api.tar.gz")
    }
    return missing
}

/** Filter Logcat on this to read what the connection card decided not to put on screen. */
private const val TAG = "TasteRouteService"''')

E(S, '''/**
 * One button and one line of answer.
 *
 * This used to be a diagnostics console — every capability the build knows about, every model
 * candidate with its milliseconds, the last failure string. All of it was real and all of it was
 * for whoever was building the app, not for whoever was using it, and it sat on the screen that
 * should have been about the person. What survives is the one question a user can actually act on:
 * is the service answering. [NimClient.lastProbe] and the full capability diff are still printed to
 * Logcat under `TasteRouteRanker`, which is where that detail belongs.
 */''',
'''/**
 * One button and one line of answer.
 *
 * This used to be a diagnostics console — every capability the build knows about, every model
 * candidate with its milliseconds, the last failure string. All of it was real and all of it was
 * for whoever was building the app, not for whoever was using it, and it sat on the screen that
 * should have been about the person.
 *
 * 2026-08-27 finished that job. Three things were still speaking to a developer: the host name
 * stood at the top of the card as permanent furniture, a failure could print a raw exception
 * message, and a paragraph underneath explained how model selection works internally. The host
 * now appears only inside a FAILED result — which is the only moment it answers a question anyone
 * is asking — raw exception text never reaches the screen, and the paragraph is one sentence
 * about what happens to the person's search when the assistant is unreachable.
 */''')

E(S, '''    SectionCard("Service") {
        Text(
            GexemyClient.baseUrl.ifBlank { "GEXEMY_BASE_URL is not set in local.properties" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(''',
'''    SectionCard("Connection") {
        Text(
            "Check that TasteRoute can reach its service. Everything on this screen works either " +
                "way — this only affects results, ratings and directions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(''')

E(S, '''                        onSuccess = { health ->
                            healthy = health.ok
                            val missing = missingCapabilities(health)
                            status = when {
                                !health.ok -> "Answered, but reported a problem."
                                missing != null -> "Connected — running an older build (${missing})."
                                else -> "Connected. Everything this app needs is switched on."
                            }
                        },
                        onFailure = { error ->
                            healthy = false
                            status = when ((error as? HttpException)?.code) {
                                404, 501 -> "Reached a server, but it is not this API — check GEXEMY_BASE_URL."
                                in 500..599 -> "The server is having a problem. Nothing to do on this end."
                                else -> error.message?.take(120) ?: "Couldn't reach it."
                            }
                        },''',
'''                        onSuccess = { health ->
                            healthy = health.ok
                            val missing = missingCapabilities(health)
                            status = when {
                                !health.ok -> "Connected, but the service is reporting a problem. Try again shortly."
                                missing.isNotEmpty() ->
                                    "Connected. Not available right now: ${missing.joinToString(", ")}."
                                else -> "Connected. Everything is working."
                            }
                        },
                        onFailure = { error ->
                            healthy = false
                            Log.w(TAG, "Health check failed against ${GexemyClient.baseUrl}", error)
                            // The host is named ONLY here. On a working install nobody ever sees
                            // it; on a broken one it is the single fact that shortens the hunt,
                            // and burying it in the log would mean plugging in a cable to read it.
                            val host = GexemyClient.baseUrl.ifBlank { "no address is set" }
                            status = when ((error as? HttpException)?.code) {
                                404, 501 -> "Something answered at $host, but it isn't TasteRoute's service."
                                in 500..599 -> "The service is having a problem. Nothing to fix on this end."
                                else -> "Couldn't reach $host. Check your connection and try again."
                            }
                        },''')

E(S, '''        Text(
            "The AI picks its own model: the service says which ones still work, this app races " +
                "those and keeps the fastest, and it checks again each time you open the app. When " +
                "none answer it ranks on the phone instead. Details go to Logcat, tag TasteRouteRanker.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )''',
'''        Text(
            "If the assistant can't be reached, TasteRoute still ranks places on your phone — " +
                "you get results either way, just not ones sorted to your taste.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )''')

# The enum name is a variable name, not a sentence.
E(S, '''                    AppState.cityLabel ?: "Location not set (${AppState.locationStatus.name.lowercase()})",''',
     '''                    AppState.cityLabel ?: locationHint(AppState.locationStatus),''')

E(S, '''@Composable
private fun Label(text: String) {''',
'''/**
 * Was `"Location not set (${AppState.locationStatus.name.lowercase()})"` — the enum constant, in
 * brackets, in the middle of a sentence. `permission_denied` is the name of a value in this
 * codebase; it is not a thing to tell somebody about their phone.
 */
private fun locationHint(status: LocationStatus): String = when (status) {
    LocationStatus.DENIED -> "Location is turned off for TasteRoute"
    LocationStatus.DISABLED -> "Location is turned off on this phone"
    LocationStatus.ASKING -> "Waiting for permission…"
    LocationStatus.UNAVAILABLE -> "Can't get a location right now"
    else -> "Location not set"
}

@Composable
private fun Label(text: String) {''')

def main():
    bufs, problems = {}, []
    for path, old, new, n in edits:
        full = os.path.join(ROOT, path)
        if path not in bufs:
            bufs[path] = open(full, encoding="utf-8").read()
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
