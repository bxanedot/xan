"""Optional HTTPS fallback for hosts where Windows PowerShell TLS is unavailable."""
import argparse
import json
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('url')
parser.add_argument('--head', action='store_true')
args = parser.parse_args()
url = urllib.parse.urlsplit(args.url)
if url.scheme != 'https' or not url.hostname or url.username or url.password:
    parser.error('Only public HTTPS URLs without embedded credentials are accepted')
request = urllib.request.Request(args.url, method='HEAD' if args.head else 'GET', headers={'User-Agent': 'xan-integration-updater/1.0'})
try:
    with urllib.request.urlopen(request, timeout=12) as response:
        if args.head:
            print(json.dumps({'status': response.status}))
        else:
            data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError('Metadata exceeds size limit')
            print(data.decode('utf-8'))
except urllib.error.HTTPError as error:
    if args.head:
        print(json.dumps({'status': error.code}))
    else:
        parser.exit(1, f'HTTP {error.code}\n')
except (OSError, ValueError) as error:
    parser.exit(1, f'HTTPS request failed: {type(error).__name__}\n')
