package work.kumarfamilynet.cinemarchive.core.model

sealed interface CatalogLoad<out T> {
    data object Loading : CatalogLoad<Nothing>
    data class Ready<T>(val value: T) : CatalogLoad<T>
    data object Failed : CatalogLoad<Nothing>
}

data class CatalogExtrasState(
    val key: CatalogExtrasKey? = null,
    val videos: CatalogLoad<List<CatalogVideo>> = CatalogLoad.Loading,
    val providers: CatalogLoad<CatalogProviders?> = CatalogLoad.Loading,
)
