package com.example.modoocommonsense.cardstackview.service

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

data class ItemResponse(
    var item: Item
)

data class Item(
    val item_id: Int,
    val title: String,
    val contents: String
)

data class UserResponse(
    var user: User
)

typealias ZonedDateTimeStr = String
data class User(
    val user_id: Int,
    val device_uuid: String,
    val created_at: ZonedDateTimeStr,
    val last_login_at: ZonedDateTimeStr,
    val bookmark_item_ids: List<Int>
)

// TODO(deploy): Remove `bookmark_item_ids`
data class UserSaveDatum(val device_uuid: String, val bookmark_item_ids: List<Int>)

data class RatingResponse(
    val rating: Rating
)

data class RatingBody(
    val item_id: Int,
    val rating: Int
)
data class Rating(
    val user_id: Int,
    val item_id: Int,
    val rating: Int,
    val rated: Boolean
)

// TODO(deploy): Refactor endpoint per model
interface ApiService {
    @GET("/api/item/{item_id}")
    fun getData(@Path("item_id") item_id: Int): Call<ItemResponse>

    @POST("api/user")
    fun POSTLogin(@Body datum: UserSaveDatum): Call<UserResponse>

    @PATCH("/api/user/{user_id}/rating")
    fun PATCHRating(@Path("user_id") user_id: Int, @Body body: RatingBody): Call<RatingResponse>
}