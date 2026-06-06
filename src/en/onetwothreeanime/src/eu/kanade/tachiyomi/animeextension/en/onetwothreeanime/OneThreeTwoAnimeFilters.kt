package eu.kanade.tachiyomi.animeextension.en.onetwothreeanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

// ============================================================
// Filter architecture:
//
//  • CheckBoxGroup  → appends  ?param[]=val&param[]=val
//  • RadioGroup     → appends  ?param=val            (single selection)
//
// The site uses array-notation for genre/country/season/year/type/language
// and scalar notation for sort.
//
// FIX: status is sent as status[] (array notation) per the real site URLs.
//      Sort now uses a blank-value sentinel at index 0 so "Default" emits
//      nothing; searchAnimeRequest then falls back to appending sort=default
//      itself – keeping the param always present (site requires it).
// ============================================================

object OneThreeTwoAnimeFilters {

    // ------------------------------------------------------------------ //
    //  Primitives                                                         //
    // ------------------------------------------------------------------ //

    /** A single named checkbox value. */
    class CheckBoxOption(val displayName: String, val queryValue: String, state: Boolean = false) : AnimeFilter.CheckBox(displayName, state)

    /** A named group of checkboxes – each checked item emits `?param[]=value`. */
    open class CheckBoxGroup(name: String, val queryParam: String, items: List<CheckBoxOption>) : AnimeFilter.Group<CheckBoxOption>(name, items) {

        /** Returns a list of (param, value) pairs for every checked item. */
        fun checkedPairs(): List<Pair<String, String>> = state.filter { it.state }.map { queryParam to it.queryValue }
    }

    /**
     * A named group of radio buttons – only one item may be active.
     * The first entry MUST have a blank queryValue to act as "All / unset".
     */
    open class RadioGroup(name: String, val queryParam: String, val options: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {

        /** Returns null when the selected value is blank (i.e. "All"). */
        fun selectedPair(): Pair<String, String>? = options[state].second.takeIf { it.isNotBlank() }?.let { queryParam to it }
    }

    // ------------------------------------------------------------------ //
    //  Concrete filter classes                                            //
    // ------------------------------------------------------------------ //

    class GenreFilter :
        CheckBoxGroup(
            "Genre",
            "genre[]",
            FiltersData.GENRES.map { CheckBoxOption(it.first, it.second) },
        )

    class CountryFilter :
        CheckBoxGroup(
            "Country",
            "country[]",
            FiltersData.COUNTRIES.map { CheckBoxOption(it.first, it.second) },
        )

    class SeasonFilter :
        CheckBoxGroup(
            "Season",
            "season[]",
            FiltersData.SEASONS.map { CheckBoxOption(it.first, it.second) },
        )

    class YearFilter :
        CheckBoxGroup(
            "Year",
            "year[]",
            FiltersData.YEARS.map { CheckBoxOption(it, it) },
        )

    class TypeFilter :
        CheckBoxGroup(
            "Type",
            "type[]",
            FiltersData.TYPES.map { CheckBoxOption(it.first, it.second) },
        )

    /**
     * Status uses radio buttons on the site (single selection only).
     *
     * FIX: The real site sends status as status[] (array notation), not status.
     * Confirmed from HAR: ?status%5B%5D=ongoing  (status[] = ongoing)
     */
    class StatusFilter :
        RadioGroup(
            "Status",
            "status[]",
            FiltersData.STATUSES,
        )

    /** Language filter – sub/dubbed, uses array notation. */
    class LanguageFilter :
        CheckBoxGroup(
            "Language",
            "language[]",
            FiltersData.LANGUAGES.map { CheckBoxOption(it.first, it.second) },
        )

    /**
     * Sort filter.
     *
     * FIX: The first option now has a blank queryValue so selectedPair()
     * returns null for "Default".  searchAnimeRequest then always appends
     * sort=default as a fallback, which keeps the param present in the URL
     * (the site needs it to recognise this as a filter request) while letting
     * the user's explicit choice override it when they pick A-Z / Z-A.
     */
    class SortFilter :
        RadioGroup(
            "Sort",
            "sort",
            FiltersData.SORT_OPTIONS,
        )

    // ------------------------------------------------------------------ //
    //  FilterList builder                                                 //
    // ------------------------------------------------------------------ //

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            GenreFilter(),
            CountryFilter(),
            SeasonFilter(),
            YearFilter(),
            TypeFilter(),
            StatusFilter(),
            LanguageFilter(),
            SortFilter(),
        )

    // ------------------------------------------------------------------ //
    //  Parsed parameters – returned to searchAnimeRequest                //
    // ------------------------------------------------------------------ //

    data class FilterParams(
        /** All (param, value) pairs ready to be appended to the URL builder. */
        val queryPairs: List<Pair<String, String>>,
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterParams {
        val pairs = mutableListOf<Pair<String, String>>()

        filters.forEach { filter ->
            when (filter) {
                is CheckBoxGroup -> pairs.addAll(filter.checkedPairs())
                is RadioGroup -> filter.selectedPair()?.let { pairs.add(it) }
                else -> { /* ignore separators / headers */ }
            }
        }

        return FilterParams(pairs)
    }

    // ------------------------------------------------------------------ //
    //  Static data                                                        //
    // ------------------------------------------------------------------ //

    private object FiltersData {

        // Pairs are (displayName, queryValue)
        val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Cars", "cars"),
            Pair("Comedy", "comedy"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-Life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        val COUNTRIES = arrayOf(
            Pair("Japan", "j"),
            Pair("China", "c"),
        )

        val SEASONS = arrayOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
        )

        // Generate years 2026 → 1958 as strings (added 2024–2026 which were missing)
        val YEARS: Array<String> = (2026 downTo 1958).map { it.toString() }.toTypedArray()

        val TYPES = arrayOf(
            Pair("Movie", "movies"),
            Pair("TV Series", "tv-series"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
        )

        // FIX: First entry has blank value → acts as "All" sentinel.
        // Status is emitted as status[] (array notation) per real site URLs.
        val STATUSES = arrayOf(
            Pair("All", ""),
            Pair("Airing", "ongoing"),
            Pair("Finished", "completed"),
            Pair("Upcoming", "upcoming"),
        )

        val LANGUAGES = arrayOf(
            Pair("Subbed", "s"),
            Pair("Dubbed", "d"),
        )

        // FIX: First entry now has blank value so "Default" → selectedPair()
        // returns null → searchAnimeRequest appends sort=default as fallback.
        // This makes "Default" truly optional while user-chosen sorts still work.
        val SORT_OPTIONS = arrayOf(
            Pair("Default", ""), // blank → no explicit sort param from here
            Pair("Name A-Z", "title_asc"),
            Pair("Name Z-A", "title_dsc"),
        )
    }
}
