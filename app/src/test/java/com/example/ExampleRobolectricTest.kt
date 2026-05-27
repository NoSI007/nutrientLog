package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.NutritionTrackerMainScreen
import com.example.ui.NutritionViewModel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Food Log & Nutrition Tracker", appName)
  }

  @Test
  fun `test main screen boots completely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val viewModel = NutritionViewModel(context as Application)
    composeTestRule.setContent {
      MyApplicationTheme {
        NutritionTrackerMainScreen(viewModel = viewModel)
      }
    }
    composeTestRule.waitForIdle()
  }
}

