/*
 * ************************************************************************
 *  NextModel.kt
 * *************************************************************************
 * Copyright © 2019 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.medialibrary.interfaces.media.AbstractMediaWrapper
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.database.models.*
import org.videolan.vlc.moviepedia.models.identify.*
import org.videolan.vlc.moviepedia.models.media.cast.CastResult
import org.videolan.vlc.moviepedia.models.media.cast.image
import org.videolan.vlc.repository.MediaMetadataRepository
import org.videolan.vlc.repository.MediaPersonRepository
import org.videolan.vlc.repository.MoviepediaApiRepository
import org.videolan.vlc.repository.PersonRepository
import org.videolan.vlc.util.getLocaleLanguages
import java.util.*
import kotlin.collections.ArrayList

class MoviepediaModel : ViewModel() {

    val apiResult: MutableLiveData<IdentifyResult> = MutableLiveData()
    val mediaResult: MutableLiveData<Media> = MutableLiveData()
    val mediaCastResult: MutableLiveData<CastResult> = MutableLiveData()
    private val repo = MoviepediaApiRepository.getInstance()
    private var searchJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }
    private var mediaJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    fun search(query: String) {
        searchJob = viewModelScope.launch {
            apiResult.value = repo.searchTitle(query)
        }
    }

    fun search(uri: Uri) {
        searchJob = viewModelScope.launch {
            apiResult.value = repo.searchMedia(uri)
        }
    }

    fun getMedia(mediaId: String) {
        mediaJob = viewModelScope.launch {
            mediaResult.value = repo.getMedia(mediaId)
        }
    }

    fun saveMediaMetadata(context: Context, media: AbstractMediaWrapper, item: Media) {
        val type = when (item.mediaType) {
            MediaType.TV_EPISODE -> 1
            else -> 0
        }
        mediaJob = viewModelScope.launch(Dispatchers.IO) {

            val mediaMetadataRepository = MediaMetadataRepository.getInstance(context)
            val personRepo = PersonRepository.getInstance(context)

            val show = when (item.mediaType) {
                MediaType.TV_EPISODE -> {
                    //todo moviepedia we have to add an API call to retrieve more TV Show info
                    val show = MediaTvshow(item.showId, item.showTitle, "", "", Date())
                    mediaMetadataRepository.insertShow(show)
                    show.moviepediaShowId
                }
                else -> null
            }

            val languages = context.getLocaleLanguages()
            val mediaMetadata = MediaMetadata(
                    media.id,
                    type,
                    item.mediaId,
                    item.title,
                    item.summary ?: "",
                    item.genre?.joinToString { genre -> genre } ?: "",
                    item.date,
                    item.country?.joinToString { genre -> genre }
                            ?: "", item.season, item.episode, item.getImageUri(languages).toString(), item.getBackdropUri(languages).toString(), show)

            val oldMediaMetadata = mediaMetadataRepository.getMetadata(media.id)
            val oldImages = oldMediaMetadata?.images

            mediaMetadataRepository.addMetadataImmediate(mediaMetadata)

            val images = ArrayList<MediaImage>()
            item.getBackdrops(languages)?.forEach {
                images.add(MediaImage(item.getImageUriFromPath(it.path), mediaMetadata.mlId, MediaImageType.BACKDROP, it.language))
            }
            item.getPosters(languages)?.forEach {
                images.add(MediaImage(item.getImageUriFromPath(it.path), mediaMetadata.mlId, MediaImageType.POSTER, it.language))
            }
            //delete old images
            oldImages?.let {
                mediaMetadataRepository.deleteImages(it.filter { images.any { newImage -> it.url == newImage.url }.not() })
            }
            mediaMetadataRepository.addImagesImmediate(images)

            val personsToAdd = ArrayList<MediaPersonJoin>()

            val castResult = repo.getMediaCast(item.mediaId)
            castResult.actor?.forEach { actor ->
                val actorEntity = Person(actor.person.personId, actor.person.name, actor.person.image())
                if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Inserting ${actor.person.name} - ${actor.person.personId} as actor")
                personRepo.addPersonImmediate(actorEntity)
                personsToAdd.add(MediaPersonJoin(mediaMetadata.mlId, actorEntity.moviepediaId, PersonType.ACTOR))

            }
            castResult.director?.forEach { actor ->
                val actorEntity = Person(actor.person.personId, actor.person.name, actor.person.image())
                if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Inserting ${actor.person.name} - ${actor.person.personId} as director")
                personRepo.addPersonImmediate(actorEntity)
                personsToAdd.add(MediaPersonJoin(mediaMetadata.mlId, actorEntity.moviepediaId, PersonType.DIRECTOR))

            }
            castResult.writer?.forEach { actor ->
                val actorEntity = Person(actor.person.personId, actor.person.name, actor.person.image())
                if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Inserting ${actor.person.name} - ${actor.person.personId} as writer")
                personRepo.addPersonImmediate(actorEntity)
                personsToAdd.add(MediaPersonJoin(mediaMetadata.mlId, actorEntity.moviepediaId, PersonType.WRITER))
            }
            castResult.musician?.forEach { actor ->
                val actorEntity = Person(actor.person.personId, actor.person.name, actor.person.image())
                if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Inserting ${actor.person.name} - ${actor.person.personId} as musician")
                personRepo.addPersonImmediate(actorEntity)
                personsToAdd.add(MediaPersonJoin(mediaMetadata.mlId, actorEntity.moviepediaId, PersonType.MUSICIAN))

            }
            castResult.producer?.forEach { actor ->
                val actorEntity = Person(actor.person.personId, actor.person.name, actor.person.image())
                if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Inserting ${actor.person.name} - ${actor.person.personId} as producer")
                personRepo.addPersonImmediate(actorEntity)
                personsToAdd.add(MediaPersonJoin(mediaMetadata.mlId, actorEntity.moviepediaId, PersonType.PRODUCER))

            }
            MediaPersonRepository.getInstance(context).removeAllFor(mediaMetadata.mlId)
            MediaPersonRepository.getInstance(context).addPersons(personsToAdd)

            //Remove orphans
            val allPersons = PersonRepository.getInstance(context).getAll()
            val allPersonJoins = MediaPersonRepository.getInstance(context).getAll()
            val personsToRemove = allPersons.filter { person -> allPersonJoins.any { personJoin -> person.moviepediaId == personJoin.personId }.not() }
            PersonRepository.getInstance(context).deleteAll(personsToRemove)
        }
    }
}