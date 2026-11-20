# ADR: Automatically Focus Search Bar on Launch

## Context
When opening app search from either the SearchBarWidget or SearchButtonWidget, SearchActivity opens above the home screen. In the current implementation, the input field is created in onCreate without requesting focus or opening the software keyboard. Users must manually tap the search bar after the screen opens to begin typing.

## Decision
We focus the search bar input field automatically upon launching SearchActivity, both on fresh creation and on single-top intent delivery. The request is dispatched on the view message queue using input.post, which ensures the view is attached and ready. We combine the legacy InputMethodManager showSoftInput call with the Android 11+ WindowInsetsController show call to guarantee IME presentation across all target Android versions.

## Consequences
- Tapping either search widget immediately opens the keyboard and places focus in the text field.
- The user can type their search query without a second interaction.
- If the widget is tapped while SearchActivity is already visible, the query is cleared and focus is renewed.
