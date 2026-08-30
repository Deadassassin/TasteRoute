package com.example.tasteroute.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Static catalog entry; the engine turns these into [RestaurantResult]s per request. */
@Serializable
data class RestaurantRecord(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val rating: Double,
    @SerialName("review_count") val reviewCount: Int,
    @SerialName("price_tier") val priceTier: Int,
    @SerialName("cuisine_tags") val cuisineTags: List<String>,
    @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(),
    @SerialName("dietary_options") val dietaryOptions: List<String> = emptyList(),
    @SerialName("image_url") val imageUrl: String? = null,
)

object SampleData {
    val restaurants: List<RestaurantRecord> by lazy {
        AppJson.decodeFromString(ListSerializer(RestaurantRecord.serializer()), CATALOG_JSON)
    }
}

// Demo catalog around downtown LA; replace with a places API later.
private val CATALOG_JSON = """
[
  {"id":"tr-001","name":"Saffron & Smoke","coordinates":{"lat":34.0459,"lng":-118.2545},"rating":4.8,"review_count":2140,"price_tier":2,"cuisine_tags":["Thai","Noodles"],"vibe_tags":["Cozy","Date night"],"dietary_options":["Vegetarian","Gluten-free"],"image_url":"https://picsum.photos/seed/tr001/640/420"},
  {"id":"tr-002","name":"La Milpa Roja","coordinates":{"lat":34.0489,"lng":-118.2401},"rating":4.6,"review_count":3320,"price_tier":1,"cuisine_tags":["Mexican","Tacos"],"vibe_tags":["Casual","Lively"],"dietary_options":["Vegetarian"],"image_url":"https://picsum.photos/seed/tr002/640/420"},
  {"id":"tr-003","name":"Kaede Omakase","coordinates":{"lat":34.0568,"lng":-118.2469},"rating":4.9,"review_count":890,"price_tier":4,"cuisine_tags":["Japanese","Sushi"],"vibe_tags":["Quiet","Date night"],"dietary_options":["Gluten-free"],"image_url":"https://picsum.photos/seed/tr003/640/420"},
  {"id":"tr-004","name":"Verdura","coordinates":{"lat":34.0441,"lng":-118.2508},"rating":4.7,"review_count":1215,"price_tier":2,"cuisine_tags":["Vegan","Mediterranean"],"vibe_tags":["Trendy","Cozy"],"dietary_options":["Vegan","Vegetarian","Gluten-free"],"image_url":"https://picsum.photos/seed/tr004/640/420"},
  {"id":"tr-005","name":"Seoul Ember","coordinates":{"lat":34.0631,"lng":-118.2355},"rating":4.5,"review_count":2780,"price_tier":3,"cuisine_tags":["Korean","BBQ"],"vibe_tags":["Lively","Family-friendly"],"dietary_options":[],"image_url":"https://picsum.photos/seed/tr005/640/420"},
  {"id":"tr-006","name":"Tonkotsu Alley","coordinates":{"lat":34.0502,"lng":-118.2431},"rating":4.4,"review_count":4100,"price_tier":1,"cuisine_tags":["Japanese","Ramen"],"vibe_tags":["Casual","Quick bite"],"dietary_options":["Vegetarian"],"image_url":"https://picsum.photos/seed/tr006/640/420"},
  {"id":"tr-007","name":"Trattoria Lume","coordinates":{"lat":34.0553,"lng":-118.2568},"rating":4.6,"review_count":1670,"price_tier":3,"cuisine_tags":["Italian","Pasta"],"vibe_tags":["Cozy","Date night"],"dietary_options":["Vegetarian"],"image_url":"https://picsum.photos/seed/tr007/640/420"},
  {"id":"tr-008","name":"Masala Hour","coordinates":{"lat":34.0290,"lng":-118.2890},"rating":4.5,"review_count":1980,"price_tier":2,"cuisine_tags":["Indian","Curry"],"vibe_tags":["Casual","Family-friendly"],"dietary_options":["Vegan","Vegetarian","Halal"],"image_url":"https://picsum.photos/seed/tr008/640/420"},
  {"id":"tr-009","name":"The Daily Patty","coordinates":{"lat":34.0479,"lng":-118.2563},"rating":4.2,"review_count":5230,"price_tier":1,"cuisine_tags":["American","Burgers"],"vibe_tags":["Quick bite","Casual"],"dietary_options":[],"image_url":"https://picsum.photos/seed/tr009/640/420"},
  {"id":"tr-010","name":"Café Marseille","coordinates":{"lat":34.0821,"lng":-118.2102},"rating":4.7,"review_count":940,"price_tier":3,"cuisine_tags":["French","Brunch"],"vibe_tags":["Quiet","Cozy"],"dietary_options":["Vegetarian"],"image_url":"https://picsum.photos/seed/tr010/640/420"},
  {"id":"tr-011","name":"Olive & Ash","coordinates":{"lat":34.0398,"lng":-118.2338},"rating":4.8,"review_count":760,"price_tier":3,"cuisine_tags":["Mediterranean","Small plates"],"vibe_tags":["Trendy","Date night"],"dietary_options":["Vegan","Vegetarian","Halal","Gluten-free"],"image_url":"https://picsum.photos/seed/tr011/640/420"},
  {"id":"tr-012","name":"Green Canteen","coordinates":{"lat":34.0587,"lng":-118.2519},"rating":4.3,"review_count":1540,"price_tier":1,"cuisine_tags":["Vegan","Bowls"],"vibe_tags":["Quick bite","Casual"],"dietary_options":["Vegan","Vegetarian","Gluten-free"],"image_url":"https://picsum.photos/seed/tr012/640/420"}
]
"""
