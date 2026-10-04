package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.utils.BankLogoResolver
import java.util.Locale

fun getBankDrawableResId(rawBankName: String, actualIssuer: String? = null): Int? {
    return BankLogoResolver.getLogo(rawBankName, actualIssuer)
}

@Composable
fun BankLogo(
    bankName: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    shapeRadius: Dp = 12.dp,
    actualIssuer: String? = null
) {
    val resId = BankLogoResolver.getLogo(bankName, actualIssuer)

    if (resId != null) {
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = bankName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )
        }
    } else {
        val cleanName = (actualIssuer ?: bankName).trim().ifEmpty { "Bank" }
        val words = cleanName.split(" ").filter { it.isNotEmpty() }
        val initials = if (words.size >= 2) {
            "${words[0].take(1)}${words[1].take(1)}".uppercase(Locale.US)
        } else {
            cleanName.take(3).uppercase(Locale.US)
        }

        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(shapeRadius))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                fontSize = if (size < 36.dp) 10.sp else 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

@Composable
fun BankLogoBadge(
    bankName: String,
    modifier: Modifier = Modifier,
    actualIssuer: String? = null
) {
    val resId = BankLogoResolver.getLogo(bankName, actualIssuer)
    if (resId != null) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            shadowElevation = 2.dp,
            modifier = modifier.size(42.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = bankName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else {
        BankLogo(bankName = bankName, modifier = modifier, size = 42.dp, shapeRadius = 12.dp, actualIssuer = actualIssuer)
    }
}
