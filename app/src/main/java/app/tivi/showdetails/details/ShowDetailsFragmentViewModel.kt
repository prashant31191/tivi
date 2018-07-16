/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.showdetails.details

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import app.tivi.SharedElementHelper
import app.tivi.data.daos.FollowedShowsDao
import app.tivi.data.entities.Episode
import app.tivi.data.entities.TiviShow
import app.tivi.datasources.trakt.RelatedShowsDataSource
import app.tivi.datasources.trakt.ShowSeasonsDataSource
import app.tivi.interactors.FollowShow
import app.tivi.interactors.SyncFollowedShowWatchedProgress
import app.tivi.interactors.UnfollowShow
import app.tivi.interactors.UpdateRelatedShows
import app.tivi.interactors.UpdateShowDetails
import app.tivi.showdetails.ShowDetailsNavigator
import app.tivi.tmdb.TmdbManager
import app.tivi.util.AppCoroutineDispatchers
import app.tivi.util.AppRxSchedulers
import app.tivi.util.Logger
import app.tivi.util.TiviViewModel
import io.reactivex.rxkotlin.Flowables
import io.reactivex.rxkotlin.plusAssign
import kotlinx.coroutines.experimental.withContext
import javax.inject.Inject

class ShowDetailsFragmentViewModel @Inject constructor(
    private val schedulers: AppRxSchedulers,
    private val dispatchers: AppCoroutineDispatchers,
    private val updateShowDetails: UpdateShowDetails,
    private val relatedShowsDataSource: RelatedShowsDataSource,
    private val updateRelatedShows: UpdateRelatedShows,
    private val seasonsDataSource: ShowSeasonsDataSource,
    private val refreshShowSeasons: UpdateShowDetails,
    private val syncFollowedShowWatchedProgress: SyncFollowedShowWatchedProgress,
    private val tmdbManager: TmdbManager,
    private val followShow: FollowShow,
    private val unfollowShow: UnfollowShow,
    private val followedShowsDao: FollowedShowsDao,
    private val logger: Logger
) : TiviViewModel() {

    var showId: Long? = null
        set(value) {
            if (field != value) {
                field = value
                if (value != null) {
                    setupLiveData()
                    refresh()
                } else {
                    _data.value = null
                }
            }
        }

    private val _data = MutableLiveData<ShowDetailsViewState>()
    val data: LiveData<ShowDetailsViewState>
        get() = _data

    private fun refresh() {
        showId?.also { id ->
            launchInteractor(updateShowDetails, UpdateShowDetails.Params(id, true))
            launchInteractor(updateRelatedShows, UpdateRelatedShows.Params(id, true))
            launchWithParent(dispatchers.io) {
                if (followedShowsDao.entryCountWithShowId(id) > 0) {
                    refreshShowSeasons(UpdateShowDetails.Params(id, true))
                    syncFollowedShowWatchedProgress(SyncFollowedShowWatchedProgress.Params(id, true))
                }
            }
        }
    }

    private fun setupLiveData() {
        showId?.let { id ->
            disposables += followedShowsDao.entryCountWithShowIdFlowable(id)
                    .subscribeOn(schedulers.io)
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .flatMap {
                        if (it) {
                            // Followed show
                            Flowables.combineLatest(
                                    updateShowDetails.observe(UpdateShowDetails.Params(id)),
                                    relatedShowsDataSource.data(id),
                                    seasonsDataSource.data(id),
                                    tmdbManager.imageProvider,
                                    ::FollowedShowDetailsViewState)
                        } else {
                            // Not followed
                            Flowables.combineLatest(
                                    updateShowDetails.observe(UpdateShowDetails.Params(id)),
                                    relatedShowsDataSource.data(id),
                                    tmdbManager.imageProvider,
                                    ::NotFollowedShowDetailsViewState)
                        }
                    }
                    .observeOn(schedulers.main)
                    .subscribe(_data::setValue, logger::e)
        }
    }

    fun addToMyShows() {
        showId?.let { id ->
            launchWithParent {
                withContext(followShow.dispatcher) {
                    followShow(FollowShow.Params(id, true))
                }
                withContext(syncFollowedShowWatchedProgress.dispatcher) {
                    syncFollowedShowWatchedProgress(SyncFollowedShowWatchedProgress.Params(id, true))
                }
            }
        }
    }

    fun removeFromMyShows() {
        showId?.let { id ->
            launchInteractor(unfollowShow, UnfollowShow.Params(id, true))
        }
    }

    fun onRelatedShowClicked(
        showDetailsNavigator: ShowDetailsNavigator,
        show: TiviShow,
        sharedElementHelper: SharedElementHelper? = null
    ) = showDetailsNavigator.showShowDetails(show, sharedElementHelper)

    fun onRelatedShowClicked(
        showDetailsNavigator: ShowDetailsNavigator,
        episode: Episode
    ) = showDetailsNavigator.showEpisodeDetails(episode)
}