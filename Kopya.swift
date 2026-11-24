import SwiftUI
import AppKit
import UniformTypeIdentifiers
import Darwin

// MARK: - Yardımcılar

final class Locked<T> {
    private let l = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T {
        get { l.lock(); defer { l.unlock() }; return v }
        set { l.lock(); v = newValue; l.unlock() }
    }
}

final class Ticket {
    private let l = NSLock()
    private var n = 0
    func next() -> Int { l.lock(); defer { l.unlock() }; n += 1; return n - 1 }
    func peek() -> Int { l.lock(); defer { l.unlock() }; return n }
}

final class Counter {
    private let l = NSLock()
    private var b: Int64 = 0
    private var cur = ""
    func add(_ n: Int64) { l.lock(); b += n; l.unlock() }
    var bytes: Int64 { l.lock(); defer { l.unlock() }; return b }
    var current: String {
        get { l.lock(); defer { l.unlock() }; return cur }
        set { l.lock(); cur = newValue; l.unlock() }
    }
}

enum Policy: String, CaseIterable, Identifiable {
    case keepBoth = "İkisini de tut"
    case overwrite = "Üzerine yaz"
    case skip = "Atla"
    var id: String { rawValue }
}

enum Mode: String, CaseIterable, Identifiable {
    case auto = "Otomatik (önerilir)"
    case ssd = "SSD (sabit, tüm çekirdekler)"
    case hdd = "HDD (sıralı)"
    var id: String { rawValue }
}

struct Cfg { let policy: Policy; let mode: Mode }

struct Tuning {
    let maxWorkers: Int
    let startWorkers: Int
    let startChunk: Int
    let segment: Int64
    let auto: Bool
    static func make(_ m: Mode) -> Tuning {
        let cores = ProcessInfo.processInfo.activeProcessorCount
        let cap = max(4, min(cores, 16))
        switch m {
        case .auto:
            return Tuning(maxWorkers: cap, startWorkers: 4, startChunk: 8 << 20, segment: 32 << 20, auto: true)
        case .ssd:
            return Tuning(maxWorkers: cap, startWorkers: cap, startChunk: 8 << 20, segment: 32 << 20, auto: false)
        case .hdd:
            return Tuning(maxWorkers: 2, startWorkers: 2, startChunk: 4 << 20, segment: 64 << 20, auto: false)
        }
    }
}

struct Item { let src: String; let dst: String; let size: Int64; let link: Bool }
struct Plan { var dirs: [String] = []; var items: [Item] = []; var total: Int64 = 0 }
struct CopyFail: Error { let msg: String }

func errStr() -> String { String(cString: strerror(errno)) }

func stat0(_ p: String) -> stat? {
    var s = stat()
    return lstat(p, &s) == 0 ? s : nil
}

func kind(_ s: stat) -> mode_t { s.st_mode & mode_t(S_IFMT) }

func uniquePath(_ p: String) -> String {
    let u = URL(fileURLWithPath: p)
    let ext = u.pathExtension
    let last = u.lastPathComponent
    let base = ext.isEmpty ? last : String(last.dropLast(ext.count + 1))
    let dir = u.deletingLastPathComponent().path
    var i = 2
    while true {
        let name = ext.isEmpty ? "\(base) \(i)" : "\(base) \(i).\(ext)"
        let c = dir + "/" + name
        if stat0(c) == nil { return c }
        i += 1
    }
}

func preadFull(_ fd: Int32, _ p: UnsafeMutableRawPointer, _ n: Int, _ off: Int64) -> Int {
    var got = 0
    while got < n {
        let r = pread(fd, p + got, n - got, off_t(off) + off_t(got))
        if r < 0 { if errno == EINTR { continue }; return -1 }
        if r == 0 { break }
        got += r
    }
    return got
}

func pwriteFull(_ fd: Int32, _ p: UnsafeMutableRawPointer, _ n: Int, _ off: Int64) -> String? {
    var done = 0
    while done < n {
        let w = pwrite(fd, p + done, n - done, off_t(off) + off_t(done))
        if w < 0 {
            if errno == EINTR { continue }
            return "Yazma hatası: " + errStr()
        }
        done += w
    }
    return nil
}

// MARK: - Plan

func buildPlan(sources: [String], dest: String) throws -> Plan {
    var plan = Plan()
    let fm = FileManager.default
    for s in sources {
        guard let st = stat0(s) else { throw CopyFail(msg: "Okunamadı: \(s)") }
        let target = dest + "/" + (s as NSString).lastPathComponent
        let k = kind(st)
        if k == mode_t(S_IFDIR) {
            if dest == s || dest.hasPrefix(s + "/") {
                throw CopyFail(msg: "Hedef, kaynak klasörün içinde olamaz: \(s)")
            }
            plan.dirs.append(target)
            if let en = fm.enumerator(atPath: s) {
                for case let rel as String in en {
                    let p = s + "/" + rel
                    guard let st2 = stat0(p) else { continue }
                    let k2 = kind(st2)
                    if k2 == mode_t(S_IFDIR) {
                        plan.dirs.append(target + "/" + rel)
                    } else if k2 == mode_t(S_IFREG) {
                        plan.items.append(Item(src: p, dst: target + "/" + rel, size: Int64(st2.st_size), link: false))
                        plan.total += Int64(st2.st_size)
                    } else if k2 == mode_t(S_IFLNK) {
                        plan.items.append(Item(src: p, dst: target + "/" + rel, size: 0, link: true))
                    }
                }
            }
        } else if k == mode_t(S_IFREG) {
            plan.items.append(Item(src: s, dst: target, size: Int64(st.st_size), link: false))
            plan.total += Int64(st.st_size)
        } else if k == mode_t(S_IFLNK) {
            plan.items.append(Item(src: s, dst: target, size: 0, link: true))
        }
    }
    return plan
}

// MARK: - Tampon havuzu

final class BufPool {
    private let lock = NSLock()
    private var avail: [UnsafeMutableRawPointer]
    private let all: [UnsafeMutableRawPointer]
    private let sem: DispatchSemaphore

    init(count: Int, size: Int) {
        let bufs = (0..<count).map { _ in UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 16384) }
        all = bufs
        avail = bufs
        sem = DispatchSemaphore(value: count)
    }
    func get() -> UnsafeMutableRawPointer {
        sem.wait()
        lock.lock(); defer { lock.unlock() }
        return avail.removeLast()
    }
    func put(_ p: UnsafeMutableRawPointer) {
        lock.lock(); avail.append(p); lock.unlock()
        sem.signal()
    }
    deinit { all.forEach { $0.deallocate() } }
}

// MARK: - Dosya işi

final class FileJob {
    let item: Item
    let dst: String
    let tmp: String
    let segCount: Int
    private let l = NSRecursiveLock()
    private var _prepared = false
    private var _remaining: Int
    private var _failure: String?
    private var _cloned = false
    private var _finalized = false

    init(item: Item, dst: String, segCount: Int) {
        self.item = item; self.dst = dst; self.tmp = dst + ".kopya-part"
        self.segCount = segCount; self._remaining = segCount
    }
    var failure: String? { l.lock(); defer { l.unlock() }; return _failure }
    func fail(_ m: String) { l.lock(); if _failure == nil { _failure = m }; l.unlock() }
    var cloned: Bool { l.lock(); defer { l.unlock() }; return _cloned }
    func markCloned() { l.lock(); _cloned = true; l.unlock() }
    var prepared: Bool { l.lock(); defer { l.unlock() }; return _prepared }
    var finalized: Bool { l.lock(); defer { l.unlock() }; return _finalized }
    func markFinalized() { l.lock(); _finalized = true; l.unlock() }
    func segmentDone() -> Bool {
        l.lock(); defer { l.unlock() }
        _remaining -= 1
        return _remaining == 0
    }
    /// İlk gelen hazırlığı yapar, diğerleri bitmesini bekler.
    func prepare(_ body: () -> Void) {
        l.lock(); defer { l.unlock() }
        if !_prepared { body(); _prepared = true }
    }
}

// MARK: - Otomatik ayar

/// Kopya sırasında anlık hızı ölçer; paralel akış sayısını ve blok boyutunu
/// tepe tırmanma yöntemiyle o diske göre ayarlar.
final class Tuner {
    static let ladderC = [4 << 20, 8 << 20, 16 << 20]
    let ladderW: [Int]
    let active: Locked<Int>
    let chunk: Locked<Int>
    let ctr: Counter
    let cancel: Locked<Bool>
    let info: Locked<String>
    let done = Locked(false)
    private(set) var wi: Int
    private(set) var ci: Int
    private(set) var probes = 0

    init(ladderW: [Int], startW: Int, startC: Int, active: Locked<Int>, chunk: Locked<Int>,
         ctr: Counter, cancel: Locked<Bool>, info: Locked<String>) {
        self.ladderW = ladderW; self.wi = startW; self.ci = startC
        self.active = active; self.chunk = chunk; self.ctr = ctr
        self.cancel = cancel; self.info = info
    }

    var workers: Int { ladderW[wi] }
    var chunkSize: Int { Tuner.ladderC[ci] }

    private func apply(_ w: Int, _ c: Int) {
        active.value = ladderW[w]
        chunk.value = Tuner.ladderC[c]
        info.value = "Otomatik: \(ladderW[w]) akış • \(Tuner.ladderC[c] >> 20) MB blok"
    }

    private func sleepCheck(_ sec: Double) -> Bool {
        var t = 0.0
        while t < sec {
            if done.value || cancel.value { return false }
            Thread.sleep(forTimeInterval: 0.05)
            t += 0.05
        }
        return true
    }

    /// Ayar değişince kısa süre bekle, sonra `sec` saniye boyunca hızı ölç (bayt/sn).
    private func measure(_ sec: Double) -> Double? {
        guard sleepCheck(0.4) else { return nil }
        let b0 = ctr.bytes
        let t0 = Date()
        guard sleepCheck(sec) else { return nil }
        let dt = Date().timeIntervalSince(t0)
        return dt > 0 ? Double(ctr.bytes - b0) / dt : 0
    }

    private func neighbors(_ w: Int, _ c: Int) -> [(Int, Int)] {
        var r: [(Int, Int)] = []
        if w + 1 < ladderW.count { r.append((w + 1, c)) }
        if w > 0 { r.append((w - 1, c)) }
        if c + 1 < Tuner.ladderC.count { r.append((w, c + 1)) }
        if c > 0 { r.append((w, c - 1)) }
        return r
    }

    func run() {
        apply(wi, ci)
        guard var base = measure(1.5) else { return }
        var queue = neighbors(wi, ci)
        while !done.value && !cancel.value {
            if queue.isEmpty {
                // Yerleşti: bir süre bekle, sonra (disk önbelleği, ısınma vb. değişebilir) yeniden dene
                if !sleepCheck(20) { return }
                guard let b = measure(1.5) else { return }
                base = b
                queue = neighbors(wi, ci)
                continue
            }
            let cand = queue.removeFirst()
            apply(cand.0, cand.1)
            guard let r = measure(1.5) else { return }
            probes += 1
            if r > base * 1.07 {
                // En az %7 daha hızlıysa benimse ve komşularını dene
                wi = cand.0; ci = cand.1; base = r
                queue = neighbors(wi, ci)
            } else {
                apply(wi, ci)
                guard let b = measure(1.5) else { return }
                base = b
            }
        }
    }
}

// MARK: - Kopyalama motoru

final class Engine {
    let cfg: Cfg
    let tune: Tuning
    let ctr: Counter
    let cancel: Locked<Bool>
    let errors: Locked<[String]>
    let pool: BufPool
    let destRoot: String
    let destDev: dev_t
    let tuneInfo: Locked<String>
    let activeBox: Locked<Int>
    let chunkBox: Locked<Int>
    let ladderW: [Int]
    let startWI: Int
    let startCI: Int
    /// Otomatik ayarın bulduğu (akış, blok) değeri; öğrenilmediyse nil
    private(set) var learned: (Int, Int)?

    init(cfg: Cfg, ctr: Counter, cancel: Locked<Bool>, errors: Locked<[String]>,
         destRoot: String, tuneInfo: Locked<String>, saved: [Int]?) {
        let tune = Tuning.make(cfg.mode)
        self.cfg = cfg; self.ctr = ctr; self.cancel = cancel; self.errors = errors
        self.destRoot = destRoot; self.tuneInfo = tuneInfo
        self.tune = tune
        // Arabellekler en büyük blok boyutuna göre ayrılır; yalnızca kullanılan kısım bellekte yer tutar
        self.pool = BufPool(count: tune.maxWorkers, size: 16 << 20)
        var s = stat()
        self.destDev = stat(destRoot, &s) == 0 ? s.st_dev : dev_t.max

        var lw = [1, 2, 3, 4, 6, 8, 10, 12, 16].filter { $0 <= tune.maxWorkers }
        if lw.last != tune.maxWorkers { lw.append(tune.maxWorkers) }
        self.ladderW = lw
        func nearest(_ arr: [Int], _ v: Int) -> Int {
            var best = 0
            for i in arr.indices where abs(arr[i] - v) < abs(arr[best] - v) { best = i }
            return best
        }
        let wi = nearest(lw, (saved?.first) ?? tune.startWorkers)
        let ci = nearest(Tuner.ladderC, (saved?.last) ?? tune.startChunk)
        self.startWI = wi
        self.startCI = ci
        if tune.auto {
            self.activeBox = Locked(lw[wi])
            self.chunkBox = Locked(Tuner.ladderC[ci])
        } else {
            self.activeBox = Locked(tune.startWorkers)
            self.chunkBox = Locked(tune.startChunk)
        }
    }

    func report(_ it: Item, _ msg: String) {
        errors.value.append("\((it.src as NSString).lastPathComponent): \(msg)")
    }

    func execute(_ items: [Item], totalBytes: Int64) {
        var jobs: [FileJob] = []
        var tasks: [(Int, Int)] = []

        for it in items {
            var dst = it.dst
            if stat0(dst) != nil {
                switch cfg.policy {
                case .skip: ctr.add(it.size); continue
                case .keepBoth: dst = uniquePath(dst)
                case .overwrite: break
                }
            }
            if it.link { copyLink(it, to: dst); continue }
            let segs = max(1, Int((it.size + tune.segment - 1) / tune.segment))
            jobs.append(FileJob(item: it, dst: dst, segCount: segs))
            for s in 0..<segs { tasks.append((jobs.count - 1, s)) }
        }

        let allJobs = jobs
        let allTasks = tasks
        let ticket = Ticket()
        let group = DispatchGroup()
        let tgroup = DispatchGroup()

        // Otomatik ayar: yalnızca ayarın anlamlı olacağı kadar büyük işlerde (>= 2 GB)
        var tuner: Tuner? = nil
        if tune.auto {
            tuneInfo.value = "Otomatik: \(activeBox.value) akış • \(chunkBox.value >> 20) MB blok"
            if totalBytes >= 2 << 30 {
                let t = Tuner(ladderW: ladderW, startW: startWI, startC: startCI,
                              active: activeBox, chunk: chunkBox, ctr: ctr, cancel: cancel, info: tuneInfo)
                tuner = t
                tgroup.enter()
                let th = Thread(block: { t.run(); tgroup.leave() })
                th.qualityOfService = .userInitiated
                th.start()
            }
        } else {
            tuneInfo.value = "\(tune.startWorkers) akış • \(tune.startChunk >> 20) MB blok (sabit)"
        }

        for w in 0..<tune.maxWorkers {
            group.enter()
            let t = Thread(block: { [self] in
                while true {
                    if cancel.value { break }
                    if w >= activeBox.value {
                        // Bu akış şu an devre dışı; iş bittiyse çık, yoksa bekle
                        if ticket.peek() >= allTasks.count { break }
                        Thread.sleep(forTimeInterval: 0.02)
                        continue
                    }
                    let i = ticket.next()
                    if i >= allTasks.count { break }
                    let (j, s) = allTasks[i]
                    runTask(allJobs[j], s)
                }
                group.leave()
            })
            t.qualityOfService = .userInteractive
            t.start()
        }
        group.wait()

        if let t = tuner {
            t.done.value = true
            tgroup.wait()
            if t.probes > 0 {
                learned = (t.workers, t.chunkSize)
                tuneInfo.value = "Seçilen ayar: \(t.workers) akış • \(t.chunkSize >> 20) MB blok"
            }
        }

        // İptal/hata sonrası yarım kalan geçici dosyaları temizle
        for j in allJobs where j.prepared && !j.finalized { unlink(j.tmp) }

        // Tüm değişiklikleri diske kalıcı olarak işle
        let fd = open(destRoot, O_RDONLY)
        if fd >= 0 { _ = fcntl(fd, F_FULLFSYNC); close(fd) }
    }

    func copyLink(_ it: Item, to dst: String) {
        var buf = [CChar](repeating: 0, count: Int(PATH_MAX) + 1)
        let n = readlink(it.src, &buf, Int(PATH_MAX))
        guard n >= 0 else { report(it, errStr()); return }
        buf[n] = 0
        unlink(dst)
        if symlink(buf, dst) != 0 { report(it, errStr()) }
    }

    func runTask(_ job: FileJob, _ seg: Int) {
        defer { if job.segmentDone() { finalize(job) } }
        job.prepare { prepare(job) }
        if job.cloned || job.failure != nil || cancel.value { return }
        ctr.current = (job.item.src as NSString).lastPathComponent
        copySegment(job, seg)
    }

    func prepare(_ job: FileJob) {
        let it = job.item
        ctr.current = (it.src as NSString).lastPathComponent
        unlink(job.tmp)

        // Aynı APFS biriminde: anında klonlama
        if let s = stat0(it.src), s.st_dev == destDev, clonefile(it.src, job.tmp, 0) == 0 {
            job.markCloned()
            ctr.add(it.size)
            return
        }

        let dfd = open(job.tmp, O_WRONLY | O_CREAT | O_TRUNC, 0o600)
        guard dfd >= 0 else { job.fail("Hedef oluşturulamadı: " + errStr()); return }
        if it.size > 0 {
            // Alanı önceden ayır: parçalanmayı azaltır, paralel yazmayı hızlandırır
            var fst = fstore_t(fst_flags: UInt32(F_ALLOCATEALL), fst_posmode: F_PEOFPOSMODE,
                               fst_offset: 0, fst_length: off_t(it.size), fst_bytesalloc: 0)
            _ = fcntl(dfd, F_PREALLOCATE, &fst)
            _ = ftruncate(dfd, off_t(it.size))
        }
        close(dfd)
    }

    func copySegment(_ job: FileJob, _ seg: Int) {
        let it = job.item
        let start = Int64(seg) * tune.segment
        let end = min(it.size, start + tune.segment)
        if start >= end { return }

        let sfd = open(it.src, O_RDONLY)
        guard sfd >= 0 else { job.fail("Kaynak açılamadı: " + errStr()); return }
        defer { close(sfd) }
        _ = fcntl(sfd, F_NOCACHE, 1)

        let dfd = open(job.tmp, O_RDWR)
        guard dfd >= 0 else { job.fail("Hedef açılamadı: " + errStr()); return }
        defer { close(dfd) }
        _ = fcntl(dfd, F_NOCACHE, 1)

        let a = pool.get()
        defer { pool.put(a) }

        var off = start
        while off < end {
            if cancel.value || job.failure != nil { return }
            let want = Int(min(Int64(chunkBox.value), end - off))

            let r = preadFull(sfd, a, want, off)
            if r != want {
                job.fail(r < 0 ? "Okuma hatası: " + errStr() : "Kaynak beklenenden kısa")
                return
            }
            if let e = pwriteFull(dfd, a, want, off) { job.fail(e); return }
            ctr.add(Int64(want))
            off += Int64(want)
        }
    }

    func finalize(_ job: FileJob) {
        defer { job.markFinalized() }
        let it = job.item
        if cancel.value { unlink(job.tmp); return }
        if let f = job.failure { unlink(job.tmp); report(it, f); return }

        if !job.cloned {
            if it.size >= 256 << 20 {
                let fd = open(job.tmp, O_RDONLY)
                if fd >= 0 {
                    if fcntl(fd, F_FULLFSYNC) != 0 { _ = fsync(fd) }
                    close(fd)
                }
            }
            if let st = stat0(it.src) { _ = chmod(job.tmp, st.st_mode & 0o7777) }
            // İzinler, zaman damgaları, xattr, ACL (en iyi çaba)
            _ = copyfile(it.src, job.tmp, nil, copyfile_flags_t(COPYFILE_ACL | COPYFILE_STAT | COPYFILE_XATTR))
        }
        if rename(job.tmp, job.dst) != 0 {
            let e = errStr()
            unlink(job.tmp)
            report(it, "Yeniden adlandırma: " + e)
        }
    }
}

// MARK: - Model

final class Model: ObservableObject {
    @Published var sources: [URL] = []
    @Published var dest: URL?
    @Published var policy: Policy = .keepBoth
    @Published var mode: Mode = .auto
    @Published var running = false
    @Published var fraction = 0.0
    @Published var speedText = ""
    @Published var etaText = ""
    @Published var current = ""
    @Published var tuneText = ""
    @Published var status = "Dosya veya klasörleri sürükleyip bırakın"
    @Published var errors: [String] = []

    let cancelFlag = Locked(false)
    private let totalBox = Locked<Int64>(0)
    private var counter: Counter?
    private var timer: Timer?
    private var activity: NSObjectProtocol?
    private var tuneRef: Locked<String>?
    private var lastBytes: Int64 = 0
    private var lastTime = Date()
    private var ema = 0.0
    private var startTime = Date()

    func addSources(_ urls: [URL]) {
        for u in urls where !sources.contains(u) { sources.append(u) }
        if !running { status = "\(sources.count) öğe hazır" }
    }

    func chooseSources() {
        let p = NSOpenPanel()
        p.canChooseFiles = true; p.canChooseDirectories = true; p.allowsMultipleSelection = true
        p.prompt = "Ekle"
        if p.runModal() == .OK { addSources(p.urls) }
    }

    func chooseDest() {
        let p = NSOpenPanel()
        p.canChooseFiles = false; p.canChooseDirectories = true; p.canCreateDirectories = true
        p.prompt = "Hedef olarak seç"
        if p.runModal() == .OK { dest = p.url }
    }

    func clear() {
        sources = []; errors = []; fraction = 0; speedText = ""; etaText = ""; current = ""
        status = "Dosya veya klasörleri sürükleyip bırakın"
    }

    func cancel() { cancelFlag.value = true }

    func start() {
        guard let dest = dest, !sources.isEmpty, !running else { return }
        running = true; errors = []; fraction = 0; speedText = ""; etaText = ""; current = ""; tuneText = ""
        status = "Hazırlanıyor…"
        cancelFlag.value = false
        let ctr = Counter(); counter = ctr
        let errBox = Locked<[String]>([])
        let cfg = Cfg(policy: policy, mode: mode)
        let srcPaths = sources.map { $0.path }
        let dpath = dest.path
        let tuneBox = Locked("")
        tuneRef = tuneBox
        let tuneKey = Model.tuneKey(src: srcPaths.first ?? "", dst: dpath)
        let saved = UserDefaults.standard.array(forKey: tuneKey) as? [Int]
        totalBox.value = 0
        lastBytes = 0; lastTime = Date(); ema = 0; startTime = Date()

        // App Nap'i ve uykuyu engelle: tam hız
        activity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled],
            reason: "Dosya kopyalama")
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.tick() }

        DispatchQueue.global(qos: .userInteractive).async { [weak self] in
            guard let self = self else { return }
            let cancel = self.cancelFlag
            do {
                let plan = try buildPlan(sources: srcPaths, dest: dpath)
                var ds = stat()
                let destDev: dev_t = stat(dpath, &ds) == 0 ? ds.st_dev : dev_t.max
                let sameVolume = srcPaths.allSatisfy { stat0($0)?.st_dev == destDev }
                if !sameVolume,
                   let v = try? URL(fileURLWithPath: dpath).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
                   let avail = v.volumeAvailableCapacityForImportantUsage, plan.total > avail {
                    throw CopyFail(msg: "Hedefte yeterli boş alan yok")
                }
                let eng = Engine(cfg: cfg, ctr: ctr, cancel: cancel, errors: errBox, destRoot: dpath,
                                 tuneInfo: tuneBox, saved: saved)
                self.totalBox.value = max(plan.total, 1)
                for d in plan.dirs {
                    try? FileManager.default.createDirectory(atPath: d, withIntermediateDirectories: true)
                }
                eng.execute(plan.items, totalBytes: plan.total)
                if let l = eng.learned {
                    // Bu disk çifti için bulunan en iyi ayarı sonraki kopyalar için hatırla
                    UserDefaults.standard.set([l.0, l.1], forKey: tuneKey)
                }
                let wasCancelled = cancel.value
                let errs = errBox.value
                let bytes = ctr.bytes
                let n = plan.items.count
                DispatchQueue.main.async { self.finish(errs: errs, cancelled: wasCancelled, files: n, bytes: bytes) }
            } catch let e as CopyFail {
                DispatchQueue.main.async { self.finish(errs: [e.msg], cancelled: false, files: 0, bytes: 0) }
            } catch {
                DispatchQueue.main.async { self.finish(errs: ["\(error)"], cancelled: false, files: 0, bytes: 0) }
            }
        }
    }

    private func tick() {
        guard let ctr = counter else { return }
        let b = ctr.bytes
        let total = totalBox.value
        let now = Date()
        let dt = now.timeIntervalSince(lastTime)
        if dt > 0 {
            let inst = Double(b - lastBytes) / dt
            ema = ema == 0 ? inst : ema * 0.7 + inst * 0.3
            lastBytes = b; lastTime = now
        }
        if total > 0 {
            fraction = min(1, Double(b) / Double(total))
            status = "\(Int(fraction * 100))%  •  \(fmt(b)) / \(fmt(total))"
            if ema > 1 {
                speedText = fmt(Int64(ema)) + "/sn"
                let rem = Double(max(total - b, 0)) / ema
                etaText = "Kalan: " + dur(rem)
            }
        }
        current = ctr.current
        tuneText = tuneRef?.value ?? ""
    }

    static func tuneKey(src: String, dst: String) -> String {
        func uuid(_ p: String) -> String {
            let v = try? URL(fileURLWithPath: p).resourceValues(forKeys: [.volumeUUIDStringKey])
            return v?.volumeUUIDString ?? "?"
        }
        return "tune.\(uuid(src))>\(uuid(dst))"
    }

    private func finish(errs: [String], cancelled: Bool, files: Int, bytes: Int64) {
        timer?.invalidate(); timer = nil
        if let a = activity { ProcessInfo.processInfo.endActivity(a); activity = nil }
        running = false
        errors = errs
        let el = Date().timeIntervalSince(startTime)
        if cancelled {
            status = "İptal edildi"
        } else {
            fraction = errs.isEmpty ? 1 : fraction
            let avg = el > 0 ? Int64(Double(bytes) / el) : 0
            status = (errs.isEmpty ? "Tamamlandı ✓" : "Bitti, \(errs.count) hata")
                + "  •  \(files) dosya, \(fmt(bytes)), ort. \(fmt(avg))/sn"
        }
        speedText = ""; etaText = ""; current = ""
        tuneText = tuneRef?.value ?? ""
    }

    private func fmt(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }
    private func dur(_ s: Double) -> String {
        let t = Int(s)
        if t >= 3600 { return "\(t / 3600) sa \((t % 3600) / 60) dk" }
        if t >= 60 { return "\(t / 60) dk \(t % 60) sn" }
        return "\(t) sn"
    }
}

// MARK: - Arayüz

struct ContentView: View {
    @StateObject var m = Model()
    @State private var targeted = false

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [6]))
                    .foregroundColor(targeted ? .accentColor : .secondary)
                VStack(spacing: 6) {
                    Image(systemName: "square.and.arrow.down.on.square").font(.system(size: 28))
                    if m.sources.isEmpty {
                        Text("Kopyalanacak dosya/klasörleri buraya bırakın")
                    } else {
                        Text("\(m.sources.count) öğe seçili").bold()
                        Text(m.sources.prefix(3).map { $0.lastPathComponent }.joined(separator: ", ")
                             + (m.sources.count > 3 ? "…" : ""))
                            .font(.caption).foregroundColor(.secondary).lineLimit(1)
                    }
                }.padding()
            }
            .frame(height: 110)
            .onDrop(of: [.fileURL], isTargeted: $targeted) { providers in
                for p in providers {
                    _ = p.loadObject(ofClass: URL.self) { url, _ in
                        if let url = url { DispatchQueue.main.async { m.addSources([url]) } }
                    }
                }
                return true
            }

            HStack {
                Button("Kaynak ekle…") { m.chooseSources() }
                Button("Temizle") { m.clear() }.disabled(m.sources.isEmpty)
                Spacer()
            }.disabled(m.running)

            HStack {
                Button("Hedef seç…") { m.chooseDest() }
                Text(m.dest?.path ?? "Hedef seçilmedi")
                    .font(.caption).foregroundColor(.secondary)
                    .lineLimit(1).truncationMode(.middle)
                Spacer()
            }.disabled(m.running)

            HStack {
                Text("Çakışma:")
                Picker("", selection: $m.policy) {
                    ForEach(Policy.allCases) { Text($0.rawValue).tag($0) }
                }.labelsHidden().frame(width: 130)
                Spacer()
            }.disabled(m.running)

            HStack {
                Text("Disk:")
                Picker("", selection: $m.mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }.labelsHidden().frame(width: 230)
                Spacer()
            }.disabled(m.running)

            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: m.fraction)
                HStack {
                    Text(m.status).font(.callout)
                    Spacer()
                    Text(m.speedText).font(.callout).monospacedDigit()
                }
                HStack {
                    Text(m.current).font(.caption).foregroundColor(.secondary)
                        .lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Text(m.etaText).font(.caption).foregroundColor(.secondary)
                }
                if !m.tuneText.isEmpty {
                    Text(m.tuneText).font(.caption2).foregroundColor(.secondary)
                }
            }

            if !m.errors.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(m.errors, id: \.self) { Text($0).font(.caption).foregroundColor(.red) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.frame(maxHeight: 80)
            }

            HStack {
                Spacer()
                if m.running {
                    Button("İptal") { m.cancel() }
                } else {
                    Button("Kopyala") { m.start() }
                        .keyboardShortcut(.defaultAction)
                        .disabled(m.sources.isEmpty || m.dest == nil)
                }
            }
        }
        .padding(18)
        .frame(width: 520)
    }
}

@main
struct KopyaApp: App {
    init() {
        // Disk G/Ç önceliği: IOPOL_TYPE_DISK(0), IOPOL_SCOPE_PROCESS(0), IOPOL_IMPORTANT(1)
        _ = setiopolicy_np(0, 0, 1)
    }
    var body: some Scene {
        WindowGroup { ContentView() }
            .windowResizability(.contentSize)
    }
}
