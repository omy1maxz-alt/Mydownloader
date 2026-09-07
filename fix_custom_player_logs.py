# The user said:
# "also Please enhance view app logs in settings so that it knkw what's the cause."
# "Now saved video on custom player.redownloading instead of turn the fully cached video into mp4.on kisskh.co. it download the highest quality,unlike before where it turn the full cache video with the quality i choose.
# what i want: I select the quality,watch them,and the cache is complete,i save them.the app turn that cache into mp4.not redownload"

# Okay, let's rethink. If I use `muxToMp4FromCache(finalUrl, title)` on the `videoUrl` and the user selected a specific quality, the `finalUrl` MUST be the variant URL.
# How do we get the variant URL accurately if we can't use `streamKeys` against a live network master playlist?
# I DID change `resolveVariantUrl` to read the master playlist FROM CACHE!
# If it reads from cache, `streamKeys` SHOULD MATCH perfectly!
# So WHY did it redownload the highest quality?
# Because in my last commit, I COMPLETELY stripped `streamKeys` from the `bundledMediaItem` to fix the "audio only" Transformer bug!
# IF `bundledMediaItem` has no `streamKeys`, `Transformer`'s track selector falls back to the default behavior (highest quality video + audio)!
# Since the highest quality was not cached, `CacheDataSource` fell back to the network and re-downloaded the highest quality!

# So my attempt to fix "Transformer audio only" by stripping streamKeys CAUSED the "Transformer redownloads highest quality" bug!
# The CORRECT fix for both is:
# Bypassing Transformer ENTIRELY for the Custom Player "Save Fully Cached Video", and USING `muxToMp4FromCache(finalUrl, title)`!
# BUT to use `muxToMp4FromCache`, we need the `finalUrl`.
# We get `finalUrl` using `resolveVariantUrl(videoUrl, streamKeyStrings)`.
# Since `resolveVariantUrl` now reads from CACHE, `streamKeyStrings` perfectly match the cached master playlist!
# So `finalUrl` will be the EXACT variant URL the user watched!
# Then `muxToMp4FromCache(finalUrl)` will read the EXACT segments from the cache without any network requests!

# In the previous iteration, I made a patch `fix_track_selection_2.py` that did exactly this: bypassed Transformer for `bundledMediaItem != null` and went straight to `muxToMp4FromCache`!
# BUT I undid that when the user gave me the second prompt before I submitted! Let me re-apply it!
