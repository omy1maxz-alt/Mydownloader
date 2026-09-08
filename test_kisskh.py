import urllib.request
import urllib.parse
req = urllib.request.Request(
    'https://kisskh.co',
    headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
    }
)
try:
    with urllib.request.urlopen(req) as response:
        print(response.read().decode('utf-8')[:500])
except Exception as e:
    print(e)
