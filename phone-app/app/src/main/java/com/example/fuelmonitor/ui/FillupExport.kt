package com.example.fuelmonitor.ui

import com.example.fuelmonitor.FillupRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun List<FillupRecord>.toSheetsTsv(): String {
    val header = "date\todometer\tgallons_added\tprice_per_gallon\ttotal_cost\tfilled_to_full\tmpg_since_last_fillup"
    val rows = sortedBy { it.timestampMs }.map { record ->
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(record.timestampMs))
        listOf(
            date,
            "%.1f".format(record.odometer),
            "%.3f".format(record.gallonsAdded),
            "%.3f".format(record.pricePerGallon),
            "%.2f".format(record.totalCost),
            if (record.filledToFull) "yes" else "no",
            record.mpgSinceLastFillup.takeIf { it > 0.0 }?.let { "%.2f".format(it) } ?: ""
        ).joinToString("\t")
    }
    return (listOf(header) + rows).joinToString("\n")
}
