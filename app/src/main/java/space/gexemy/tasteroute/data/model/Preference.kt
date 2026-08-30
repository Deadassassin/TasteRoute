package space.gexemy.tasteroute.data.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Font override. The first four are system faces — always present, no download, no APK weight.
 * The rest are Google Fonts pulled through the platform's downloadable-font provider; [label] is
 * the family name the provider expects, so it doubles as the menu text.
 */
enum class FontChoice(val label: String, val note: String) {
    SYSTEM("System default", "Whatever your phone uses"),
    SYSTEM_SERIF("System serif", "No download"),
    SYSTEM_MONO("System mono", "No download"),
    SYSTEM_CURSIVE("System cursive", "No download"),
    INTER("Inter", "Clean UI sans"),
    ROBOTO("Roboto", "Android classic"),
    OPEN_SANS("Open Sans", "Friendly and wide"),
    NUNITO("Nunito", "Rounded and soft"),
    POPPINS("Poppins", "Geometric"),
    MANROPE("Manrope", "Modern sans"),
    DM_SANS("DM Sans", "Compact sans"),
    WORK_SANS("Work Sans", "Sturdy"),
    RUBIK("Rubik", "Slightly rounded"),
    SPACE_GROTESK("Space Grotesk", "Techy"),
    LORA("Lora", "Readable serif"),
    MERRIWEATHER("Merriweather", "Bookish serif"),
    PLAYFAIR_DISPLAY("Playfair Display", "High-contrast serif"),
    SOURCE_CODE_PRO("Source Code Pro", "Monospace"),
    ATKINSON_HYPERLEGIBLE("Atkinson Hyperlegible", "Built for low vision"),
    ;

    val downloadable: Boolean get() = ordinal >= INTER.ordinal
}
