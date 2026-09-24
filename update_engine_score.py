import sys

with open("app/src/main/java/com/omymaxz/download/MediaCandidate.kt", "r") as f:
    content = f.read()

# Requirement: "If a candidate has a known finite video duration: duration > 0 AND duration < 60 seconds it must NOT appear in the normal detected-media/download list... Do NOT use the old 30-second threshold. MIN_ACCEPTED_VIDEO_DURATION_SECONDS = 60"
# "Do NOT make: >=60 -> enormous bonus"

# Fix scoring rules:
old_scoring = """            // Duration penalization / boost
            if (durationSec in 1..29) score -= 20
            else if (durationSec >= 30) score += 10"""

new_scoring = """            // Duration handling: do not heavily penalize or boost here, let the hard filter handle <60s eligibility in MainActivity.
            if (durationSec >= 60) score += 5"""

content = content.replace(old_scoring, new_scoring)

# Remove the explicit high score for just isActivePlayer (50 is too high for just active, need verified)
old_active = "if (isActivePlayer) score += 50"
new_active = "if (isActivePlayer) score += 15"
content = content.replace(old_active, new_active)

old_tel = """            // Telemetry Scoring
            telemetry?.let { t ->
                if (!t.paused) score += 35
                if (t.currentTimeSec > 1.0) score += 20
                if (t.durationSec > 30.0) score += 10
                if (t.fullscreen) score += 30"""

new_tel = """            // Telemetry Scoring
            telemetry?.let { t ->
                if (!t.paused) score += 15
                if (t.currentTimeSec > 3.0) score += 10
                if (t.durationSec >= 60.0) score += 5
                if (t.fullscreen) score += 30"""

content = content.replace(old_tel, new_tel)

with open("app/src/main/java/com/omymaxz/download/MediaCandidate.kt", "w") as f:
    f.write(content)

print("Done MediaCandidate")
