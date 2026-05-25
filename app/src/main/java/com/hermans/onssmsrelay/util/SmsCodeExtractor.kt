package com.hermans.onssmsrelay.util

object SmsCodeExtractor {

    private val codePattern = Regex("\\b(\\d{4,8})\\b")

    fun extract(messageBody: String): String? {
        return codePattern.find(messageBody)?.groupValues?.get(1)
    }
}
