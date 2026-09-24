import sys

# Ah! There is `fabShowMedia` which shows the `ic_download` button on the top toolbar when media is detected.
# But the user specifically asked for: "A floating button that appears from the WebMedia/browser screen when the detector has identified and VERIFIED that actual media is playing."

# I will add a new FloatingActionButton or a custom floating layout to `activity_main.xml` inside the `mainContent` FrameLayout so it floats over the WebView.

with open("app/src/main/res/layout/activity_main.xml", "r") as f:
    content = f.read()

old_content = """        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/bookmarkRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="visible"
            android:paddingTop="0dp"
            android:clipToPadding="false" />

    </FrameLayout>"""

new_content = """        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/bookmarkRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="visible"
            android:paddingTop="0dp"
            android:clipToPadding="false" />

        <LinearLayout
            android:id="@+id/floatingDetectorUI"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:background="@drawable/rounded_background"
            android:backgroundTint="#AA000000"
            android:layout_gravity="bottom|end"
            android:layout_margin="24dp"
            android:gravity="center_vertical"
            android:padding="8dp"
            android:elevation="6dp"
            android:visibility="gone"
            tools:visibility="visible">

            <ImageView
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:src="@android:drawable/ic_media_play"
                app:tint="#4CAF50" />

            <TextView
                android:id="@+id/txtDetectorState"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Verified: HLS 1080p"
                android:textColor="#FFFFFF"
                android:textSize="12sp"
                android:textStyle="bold"
                android:layout_marginStart="8dp"
                android:layout_marginEnd="8dp"/>

        </LinearLayout>

    </FrameLayout>"""

content = content.replace(old_content, new_content)

with open("app/src/main/res/layout/activity_main.xml", "w") as f:
    f.write(content)

print("Added floatingDetectorUI to activity_main.xml")
