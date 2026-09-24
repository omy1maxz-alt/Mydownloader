import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

import re
old_syntax = re.search(r"                                            fetchSubtitleSnippet\(mediaFile\).*?                                        \}.sniff\(iframeUrl\)", content, re.DOTALL)
if old_syntax:
    new_syntax = """                                            fetchSubtitleSnippet(mediaFile)
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Error processing sniffed media URL: ${e.message}")
                                }
                            }
                        }).sniff(iframeUrl)"""
    content = content.replace(old_syntax.group(0), new_syntax)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
