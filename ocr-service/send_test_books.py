"""Send all test books to server via gRPC and compare with expected results."""
import json
import grpc
import sys
import time
import urllib.request
from pathlib import Path
import subprocess

PROTO_FILE = Path('../server/src/main/proto/book_service.proto')
SERVER_GRPC = 'bookworm.dobby:9090'
SERVER_HTTP = 'http://bookworm.dobby:4040'
TEST_DIR = Path('test_data/rus')
POLL_TIMEOUT = 120   # seconds to wait for processing to complete
POLL_INTERVAL = 2

def compile_proto():
    out_dir = Path('_proto_gen')
    out_dir.mkdir(exist_ok=True)
    result = subprocess.run([
        sys.executable, '-m', 'grpc_tools.protoc',
        f'--proto_path={PROTO_FILE.parent}',
        f'--python_out={out_dir}',
        f'--grpc_python_out={out_dir}',
        str(PROTO_FILE.name),
    ], capture_output=True, text=True)
    if result.returncode != 0:
        print("Proto compile error:", result.stderr)
        sys.exit(1)
    grpc_file = out_dir / 'book_service_pb2_grpc.py'
    content = grpc_file.read_text()
    content = content.replace('import book_service_pb2', 'from _proto_gen import book_service_pb2')
    grpc_file.write_text(content)
    return out_dir

out_dir = compile_proto()
sys.path.insert(0, '.')
sys.path.insert(0, str(out_dir.parent))

from _proto_gen import book_service_pb2
from _proto_gen import book_service_pb2_grpc

TYPE_MAP = {
    'cover':     book_service_pb2.COVER,
    'info':      book_service_pb2.INFO_PAGE,
    'info2':     book_service_pb2.INFO_PAGE,
    'info_page': book_service_pb2.INFO_PAGE,
    'barcode':   book_service_pb2.BARCODE,
    'title':     book_service_pb2.TITLE_PAGE,
    'back':      book_service_pb2.BACK,
}

def image_type_for_file(name: str):
    stem = Path(name).stem.lower()
    for key, val in TYPE_MAP.items():
        if stem.startswith(key):
            return val
    return book_service_pb2.IMAGE_TYPE_UNSPECIFIED

def send_book(stub, book_dir: Path, book_name: str):
    images = []
    for img_path in sorted(book_dir.iterdir()):
        if img_path.suffix.lower() not in ('.jpg', '.jpeg', '.jfif', '.png'):
            continue
        itype = image_type_for_file(img_path.name)
        if itype == book_service_pb2.IMAGE_TYPE_UNSPECIFIED:
            continue
        images.append(book_service_pb2.PageImage(type=itype, data=img_path.read_bytes()))

    if not images:
        return None, "no images"

    req = book_service_pb2.UploadBookRequest(
        metadata=book_service_pb2.BookMetadata(client_ref_id=book_name, language='rus'),
        images=images,
    )
    try:
        return stub.UploadBook(req, timeout=120), None
    except grpc.RpcError as e:
        return None, str(e)

def wait_for_processing(book_id: str) -> dict:
    """Poll /api/books/{id}/processing-status until done, then return full book JSON."""
    deadline = time.time() + POLL_TIMEOUT
    while time.time() < deadline:
        try:
            url = f"{SERVER_HTTP}/api/books/{book_id}/processing-status"
            with urllib.request.urlopen(url, timeout=5) as r:
                status = json.loads(r.read())
            if not status.get('active', True):
                break
        except Exception:
            pass
        time.sleep(POLL_INTERVAL)

    try:
        with urllib.request.urlopen(f"{SERVER_HTTP}/api/books/{book_id}", timeout=5) as r:
            return json.loads(r.read())
    except Exception as e:
        return {}

def load_expected(book_dir: Path):
    exp_file = book_dir / 'expected.json'
    if not exp_file.exists():
        return None
    with open(exp_file, encoding='utf-8') as f:
        return json.load(f)

def score_result(book: dict, expected: dict):
    if expected is None:
        return None, []

    def authors_str(book):
        authors = book.get('authors') or []
        return ', '.join(a.get('name', '') for a in authors)

    def publisher_str(book):
        p = book.get('publisher')
        return p.get('name', '') if p else ''

    field_getters = {
        'title':     lambda b: b.get('title') or '',
        'author':    authors_str,
        'publisher': publisher_str,
        'year':      lambda b: str(b.get('publicationYear') or ''),
        'isbn':      lambda b: b.get('isbn') or '',
    }

    hits, total, mismatches = 0, 0, []
    for field, getter in field_getters.items():
        exp_val = expected.get(field)
        if exp_val in (None, 'unknown', 0, ''):
            continue
        total += 1
        got = getter(book)
        exp_str = str(exp_val).strip()
        if got and exp_str in got:
            hits += 1
        else:
            mismatches.append(f"{field}: got={repr(got):<40} exp={repr(exp_val)}")

    return (hits, total), mismatches

def main():
    channel = grpc.insecure_channel(SERVER_GRPC)
    stub = book_service_pb2_grpc.BookServiceStub(channel)

    print(f"\nSending test books to {SERVER_GRPC} (waiting for async processing)\n")
    print(f"{'Book':<15} {'Imgs':<5} {'Score':<7} Result")
    print('-' * 90)

    for book_dir in sorted(TEST_DIR.iterdir()):
        if not book_dir.is_dir():
            continue
        book_name = book_dir.name
        expected = load_expected(book_dir)

        resp, err = send_book(stub, book_dir, book_name)
        if err:
            print(f"{book_name:<15}       {'SKIP':<7} {err}")
            continue

        img_count = sum(
            1 for p in book_dir.iterdir()
            if p.suffix.lower() in ('.jpg', '.jpeg', '.jfif', '.png')
            and image_type_for_file(p.name) != book_service_pb2.IMAGE_TYPE_UNSPECIFIED
        )

        print(f"{book_name:<15} {img_count:<5} {'...':<7} waiting...", end='\r', flush=True)
        book = wait_for_processing(resp.book_id)

        score, mismatches = score_result(book, expected)
        score_str = f"{score[0]}/{score[1]}" if score else "n/a"
        status = 'OK' if not mismatches else 'MISMATCH'
        print(f"{book_name:<15} {img_count:<5} {score_str:<7} {status}")
        for m in mismatches:
            print(f"  !! {m}")

    channel.close()
    print()

if __name__ == '__main__':
    main()
