import re

with open('app/src/main/res/layout/custom_exo_player_control_view.xml', 'r') as f:
    content = f.read()

replacement = """      <ImageButton android:id="@id/exo_subtitle"
          style="@style/ExoStyledControls.Button.Bottom.CC"/>

      <ImageButton android:id="@id/exo_settings"
          style="@style/ExoStyledControls.Button.Bottom.Settings"/>

      <ImageButton
          android:id="@+id/fab_more_options"
          style="@style/ExoStyledControls.Button.Bottom"
          android:src="@android:drawable/ic_menu_more"
          android:padding="8dp"
          android:scaleType="centerInside"
          android:contentDescription="More Options" />

      <ImageButton android:id="@id/exo_fullscreen"
          style="@style/ExoStyledControls.Button.Bottom.FullScreen"/>"""

# Need to replace the basic_controls section
start = '<ImageButton android:id="@id/exo_subtitle"'
end = '<ImageButton android:id="@id/exo_overflow_show"'

start_idx = content.find(start)
end_idx = content.find(end, start_idx)

if start_idx != -1 and end_idx != -1:
    new_content = content[:start_idx] + replacement + '\n\n      ' + content[end_idx:]
    with open('app/src/main/res/layout/custom_exo_player_control_view.xml', 'w') as f:
        f.write(new_content)
    print("Fixed custom_exo_player_control_view.xml")
else:
    print("Could not find section to replace.")
