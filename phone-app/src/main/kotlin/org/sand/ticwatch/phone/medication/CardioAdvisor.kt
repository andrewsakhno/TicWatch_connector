package org.sand.ticwatch.phone.medication

import androidx.annotation.StringRes
import org.sand.ticwatch.phone.R

/** Advice for a geomagnetically disturbed day: general cardio points, independent of the (externally managed) therapy. */
class CardioAdvisor {

    @StringRes
    fun advice(): List<Int> = listOf(R.string.advice_cuff_check, R.string.advice_keep_schedule, R.string.advice_limit_exercise)
}
