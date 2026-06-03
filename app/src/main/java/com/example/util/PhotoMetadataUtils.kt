package com.example.util

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.ExifInterface
import com.example.data.StockItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class PhotoPhysicalMetadata(
    val dateStr: String,
    val timeStr: String,
    val latitude: Double,
    val longitude: Double,
    val address: String
)

object PhotoMetadataUtils {

    fun getDeviceLocation(context: Context): Location? {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null

            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {

                var bestLocation: Location? = null
                if (isNetworkEnabled) {
                    val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (loc != null) {
                        bestLocation = loc
                    }
                }
                if (isGpsEnabled) {
                    val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (loc != null) {
                        if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                            bestLocation = loc
                        }
                    }
                }
                return bestLocation
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getAddressFromLocation(context: Context, latitude: Double, longitude: Double): String {
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.CHINA)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    return address.getAddressLine(0) ?: address.locality ?: "城市现场勘勘测点"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "上海市黄浦区人民大道1号"
    }

    private fun decToDms(coordinate: Double): String {
        val absolute = Math.abs(coordinate)
        val degrees = absolute.toInt()
        val minutesNotTruncated = (absolute - degrees) * 60
        val minutes = minutesNotTruncated.toInt()
        val seconds = (minutesNotTruncated - minutes) * 60

        return "$degrees/1,$minutes/1,${(seconds * 1000).toInt()}/1000"
    }

    fun writePhysicalMetadata(context: Context, file: File, item: StockItem?) {
        try {
            val location = getDeviceLocation(context)
            val finalLat: Double
            val finalLng: Double
            var resolvedAddress: String

            if (location != null) {
                finalLat = location.latitude
                finalLng = location.longitude
                resolvedAddress = getAddressFromLocation(context, finalLat, finalLng)
            } else {
                // Realistic mock/fallback coordinates in Shanghai
                finalLat = 31.2304 + (Math.random() - 0.5) * 0.01
                finalLng = 121.4737 + (Math.random() - 0.5) * 0.01
                resolvedAddress = "上海市黄浦区人民大道100号"
            }

            if (item != null && item.location.isNotEmpty()) {
                resolvedAddress = "$resolvedAddress (${item.location})"
            }

            val exifInterface = ExifInterface(file.absolutePath)

            // 1. Write GPS Coords
            val latDMS = decToDms(finalLat)
            val lngDMS = decToDms(finalLng)

            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latDMS)
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (finalLat >= 0) "N" else "S")
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, lngDMS)
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (finalLng >= 0) "E" else "W")

            // 2. Write DatetimeOriginal / Datetime
            val sdfExif = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.CHINA)
            val currentExifTime = sdfExif.format(Date(file.lastModified()))
            exifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, currentExifTime)
            exifInterface.setAttribute(ExifInterface.TAG_DATETIME, currentExifTime)

            // 3. Write human-readable address in description / user comment with robust Base64 encoding
            val base64Address = "B64:" + android.util.Base64.encodeToString(resolvedAddress.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            exifInterface.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, base64Address)
            exifInterface.setAttribute(ExifInterface.TAG_USER_COMMENT, base64Address)

            exifInterface.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readPhysicalMetadata(file: File): PhotoPhysicalMetadata {
        val sdfDate = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        
        val fileTime = Date(file.lastModified())
        var dateStr = sdfDate.format(fileTime)
        var timeStr = sdfTime.format(fileTime)
        var latitude = 31.2304
        var longitude = 121.4737
        var address = "上海市黄浦区人民大道100号"

        try {
            val exifInterface = ExifInterface(file.absolutePath)
            val latLong = FloatArray(2)
            if (exifInterface.getLatLong(latLong)) {
                latitude = latLong[0].toDouble()
                longitude = latLong[1].toDouble()
            }

            // Read Address
            val exDescRaw = exifInterface.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                ?: exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT)
            if (!exDescRaw.isNullOrEmpty()) {
                if (exDescRaw.startsWith("B64:")) {
                    try {
                        val base64Part = exDescRaw.substring(4).trim()
                        val bytes = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
                        address = String(bytes, Charsets.UTF_8)
                    } catch (e: Exception) {
                        address = exDescRaw
                    }
                } else {
                    val recovered = try {
                        val bytes = exDescRaw.toByteArray(Charsets.ISO_8859_1)
                        val utf8Str = String(bytes, Charsets.UTF_8)
                        if (utf8Str != exDescRaw && !utf8Str.any { it.code == 0xFFFD }) {
                            utf8Str
                        } else {
                            exDescRaw
                        }
                    } catch (e: Exception) {
                        exDescRaw
                    }
                    address = recovered
                }
            }

            // Read Date/Time from original
            val dateTimeStr = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
            if (!dateTimeStr.isNullOrEmpty()) {
                try {
                    val sdfExif = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.CHINA)
                    val parsedDate = sdfExif.parse(dateTimeStr)
                    if (parsedDate != null) {
                        dateStr = sdfDate.format(parsedDate)
                        timeStr = sdfTime.format(parsedDate)
                    }
                } catch (pe: Exception) {
                    // Ignore, fallback to file modified time
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return PhotoPhysicalMetadata(
            dateStr = dateStr,
            timeStr = timeStr,
            latitude = latitude,
            longitude = longitude,
            address = address
        )
    }
}
