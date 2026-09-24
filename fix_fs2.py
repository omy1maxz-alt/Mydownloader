import sys

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    content = f.read()

content = content.replace("            val closeButton = bubbleView!!.findViewById<ImageView>(R.id.btn_close_bubble)", "            val closeButton = bubbleView!!.findViewById<ImageView>(R.id.btn_close_bubble)\n            val bubbleIcon = bubbleView!!.findViewById<ImageView>(R.id.img_bubble_icon)")

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(content)
