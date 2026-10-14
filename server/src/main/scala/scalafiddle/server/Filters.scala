package scalafiddle.server

import javax.inject.Inject
import play.api.http.DefaultHttpFilters
import play.filters.cors.CORSFilter
import play.filters.gzip.GzipFilter

// SameSiteFilter is innermost so that it sees the cookies set by the action directly.
class Filters @Inject()(corsFilter: CORSFilter, gzipFilter: GzipFilter, sameSiteFilter: SameSiteFilter)
    extends DefaultHttpFilters(corsFilter, gzipFilter, sameSiteFilter)
