package com.receiptscan.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SignalWifi4Bar
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.ui.graphics.vector.ImageVector

/** Icon names used in the designs (handover 3.6) mapped to Material Symbols Rounded. */
object AppIcons {
    val receipt: ImageVector get() = Icons.Rounded.ReceiptLong
    val bill: ImageVector get() = Icons.Rounded.Payments
    val doc: ImageVector get() = Icons.Rounded.Description
    val board: ImageVector get() = Icons.Rounded.Dashboard
    val book: ImageVector get() = Icons.AutoMirrored.Rounded.MenuBook
    val text: ImageVector get() = Icons.Rounded.TextFields
    val product: ImageVector get() = Icons.Rounded.Inventory2
    val nutrition: ImageVector get() = Icons.Rounded.Science
    val expiry: ImageVector get() = Icons.Rounded.Timer
    val food: ImageVector get() = Icons.Rounded.Restaurant
    val qr: ImageVector get() = Icons.Rounded.QrCode2
    val translate: ImageVector get() = Icons.Rounded.Translate
    val math: ImageVector get() = Icons.Rounded.Calculate
    val counter: ImageVector get() = Icons.Rounded.Numbers
    val plant: ImageVector get() = Icons.Rounded.Eco
    val wifi: ImageVector get() = Icons.Rounded.Wifi
    val wifiOff: ImageVector get() = Icons.Rounded.WifiOff
    val signal: ImageVector get() = Icons.Rounded.SignalWifi4Bar
    val camera: ImageVector get() = Icons.Rounded.CameraAlt
    val image: ImageVector get() = Icons.Rounded.Image
    val gallery: ImageVector get() = Icons.Rounded.PhotoLibrary
    val import: ImageVector get() = Icons.Rounded.FileUpload
    val scan: ImageVector get() = Icons.Rounded.DocumentScanner
    val crop: ImageVector get() = Icons.Rounded.Crop
    val rotate: ImageVector get() = Icons.Rounded.RotateRight
    val enhance: ImageVector get() = Icons.Rounded.AutoAwesome
    val flash: ImageVector get() = Icons.Rounded.FlashOn
    val help: ImageVector get() = Icons.Rounded.HelpOutline
    val search: ImageVector get() = Icons.Rounded.Search
    val settings: ImageVector get() = Icons.Rounded.Settings
    val home: ImageVector get() = Icons.Rounded.Home
    val library: ImageVector get() = Icons.Rounded.LocalLibrary
    val tools: ImageVector get() = Icons.Rounded.Apps
    val insights: ImageVector get() = Icons.Rounded.Insights
    val alert: ImageVector get() = Icons.Rounded.WarningAmber
    val error: ImageVector get() = Icons.Rounded.ErrorOutline
    val check: ImageVector get() = Icons.Rounded.Check
    val checkCircle: ImageVector get() = Icons.Rounded.CheckCircle
    val close: ImageVector get() = Icons.Rounded.Close
    val back: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
    val chev: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    val more: ImageVector get() = Icons.Rounded.MoreVert
    val edit: ImageVector get() = Icons.Rounded.Edit
    val trash: ImageVector get() = Icons.Rounded.Delete
    val share: ImageVector get() = Icons.Rounded.Share
    val copy: ImageVector get() = Icons.Rounded.ContentCopy
    val download: ImageVector get() = Icons.Rounded.Download
    val external: ImageVector get() = Icons.AutoMirrored.Rounded.OpenInNew
    val link: ImageVector get() = Icons.Rounded.Link
    val shield: ImageVector get() = Icons.Rounded.Shield
    val lock: ImageVector get() = Icons.Rounded.Lock
    val bell: ImageVector get() = Icons.Rounded.Notifications
    val clock: ImageVector get() = Icons.Rounded.Schedule
    val calendar: ImageVector get() = Icons.Rounded.CalendarMonth
    val event: ImageVector get() = Icons.Rounded.Event
    val users: ImageVector get() = Icons.Rounded.People
    val user: ImageVector get() = Icons.Rounded.Person
    val trend: ImageVector get() = Icons.AutoMirrored.Rounded.TrendingUp
    val tag: ImageVector get() = Icons.Rounded.LocalOffer
    val sell: ImageVector get() = Icons.Rounded.Sell
    val grid: ImageVector get() = Icons.Rounded.GridView
    val sparkle: ImageVector get() = Icons.Rounded.AutoAwesome
    val info: ImageVector get() = Icons.Rounded.Info
    val eye: ImageVector get() = Icons.Rounded.Visibility
    val offline: ImageVector get() = Icons.Rounded.WifiOff
    val storage: ImageVector get() = Icons.Rounded.Storage
    val palette: ImageVector get() = Icons.Rounded.Palette
    val sun: ImageVector get() = Icons.Rounded.WbSunny
    val moon: ImageVector get() = Icons.Rounded.Nightlight
    val pause: ImageVector get() = Icons.Rounded.Pause
    val play: ImageVector get() = Icons.Rounded.PlayArrow
    val stop: ImageVector get() = Icons.Rounded.Stop
    val plus: ImageVector get() = Icons.Rounded.Add
    val minus: ImageVector get() = Icons.Rounded.Remove
    val undo: ImageVector get() = Icons.AutoMirrored.Rounded.Undo
    val refresh: ImageVector get() = Icons.Rounded.Refresh
    val sort: ImageVector get() = Icons.AutoMirrored.Rounded.Sort
    val filter: ImageVector get() = Icons.Rounded.FilterList
    val tune: ImageVector get() = Icons.Rounded.Tune
    val globe: ImageVector get() = Icons.Rounded.Language
    val phone: ImageVector get() = Icons.Rounded.Phone
    val email: ImageVector get() = Icons.Rounded.Email
    val basket: ImageVector get() = Icons.Rounded.ShoppingBasket
    val cup: ImageVector get() = Icons.Rounded.Fastfood
    val mic: ImageVector get() = Icons.Rounded.Mic
    val keyboard: ImageVector get() = Icons.Rounded.Keyboard
    val bolt: ImageVector get() = Icons.Rounded.Bolt
    val leaf: ImageVector get() = Icons.Rounded.Spa
    val grass: ImageVector get() = Icons.Rounded.Grass
}
