package com.homelibrary.client.data

import androidx.room.Embedded
import androidx.room.Relation

data class BookWithPages(
    @Embedded val book: BookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId"
    )
    val pages: List<PageEntity>
)
