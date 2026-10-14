"use client";

import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import type { Dispatch, ReactNode, SetStateAction } from "react";
import { Button } from "@/components/ui/button";
import { Activity, ArrowDownRight, ArrowRight, ArrowUpRight, Banknote, Bell, Check, ChevronDown, ChevronRight, CircleHelp, Command, Copy, CreditCard, Eye, EyeOff, Fingerprint, FolderKanban, Globe2, KeyRound, Landmark, LayoutDashboard, LockKeyhole, Menu, MoreHorizontal, Plus, Search, Settings2, Shield, ShieldCheck, SlidersHorizontal, Sparkles, Star, Tags, Wallet, X, Zap } from "lucide-react";

type Section = "Overview" | "Bank vault" | "Password vault" | "Custom vaults" | "Categories" | "Security center" | "Settings";
type Bank = { name: string; type: string; last4: string; holder: string; tone: string; mark: string; favorite: boolean };
type Login = { name: string; url: string; user: string; group: string; initials: string; tint: string; favorite: boolean };
const seedBanks: Bank[] = [
  { name: "Monarch Private", type: "Private banking", last4: "4821", holder: "A. Morgan", tone: "blue", mark: "M", favorite: true },
  { name: "Northstar Bank", type: "Everyday account", last4: "9016", holder: "A. Morgan", tone: "violet", mark: "N", favorite: false },
  { name: "Crescent Reserve", type: "Savings", last4: "2374", holder: "A. Morgan", tone: "green", mark: "C", favorite: false },
];
const seedLogins: Login[] = [
  { name: "Linear", url: "linear.app", user: "alex@studio.co", group: "Work", initials: "L", tint: "violet", favorite: true },
  { name: "Notion", url: "notion.so", user: "alex@studio.co", group: "Work", initials: "N", tint: "sand", favorite: false },
  { name: "Figma", url: "figma.com", user: "alex@studio.co", group: "Design", initials: "F", tint: "rose", favorite: false },
  { name: "GitHub", url: "github.com", user: "amorgan-dev", group: "Development", initials: "GH", tint: "slate", favorite: true },
];
const primaryNav: { label: Section; icon: typeof LayoutDashboard }[] = [
  { label: "Overview", icon: LayoutDashboard }, { label: "Bank vault", icon: Landmark }, { label: "Password vault", icon: KeyRound }, { label: "Custom vaults", icon: FolderKanban },
];
const manageNav: { label: Section; icon: typeof LayoutDashboard }[] = [
  { label: "Categories", icon: Tags }, { label: "Security center", icon: ShieldCheck }, { label: "Settings", icon: Settings2 },
];

function IButton({ children, label, onClick, className = "" }: { children: ReactNode; label: string; onClick?: () => void; className?: string }) {
  return <button className={`icon-button ${className}`} aria-label={label} title={label} onClick={onClick}>{children}</button>;
}
function Mark({ initials, tint }: { initials: string; tint: string }) { return <div className={`app-mark ${tint}`}>{initials}</div>; }
function PageHeading({ eyebrow, title, description, action, onAction }: { eyebrow: string; title: string; description: string; action?: string; onAction?: () => void }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{description}</p></div>{action && <Button className="button-primary" onClick={onAction}><Plus size={16} />{action}</Button>}</div>;
}
function Ring({ value, size = 116 }: { value: number; size?: number }) {
  return <div className="ring-wrap" style={{ width: size, height: size }}><svg viewBox="0 0 120 120" className="ring-svg" aria-label={`Security score ${value}`}><circle className="ring-track" cx="60" cy="60" r="51" /><motion.circle className="ring-value" cx="60" cy="60" r="51" initial={{ pathLength: 0 }} animate={{ pathLength: value / 100 }} transition={{ duration: 1.1, ease: "easeOut" }} /></svg><div className="ring-center"><strong>{value}</strong><span>score</span></div></div>;
}

export default function Home() {
  const [section, setSection] = useState<Section>("Overview");
  const [banks, setBanks] = useState(seedBanks);
  const [logins, setLogins] = useState(seedLogins);
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [mobileNav, setMobileNav] = useState(false);
  const [filter, setFilter] = useState("All items");
  const [toast, setToast] = useState("");
  const [addType, setAddType] = useState<"Bank account" | "Password">("Password");
  const [newName, setNewName] = useState("");
  const [newUser, setNewUser] = useState("");
  const [light, setLight] = useState(false);

  useEffect(() => {
    const keys = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setCommandOpen(v => !v); }
      if (e.key === "Escape") { setCommandOpen(false); setAddOpen(false); setSearchOpen(false); }
    };
    window.addEventListener("keydown", keys); return () => window.removeEventListener("keydown", keys);
  }, []);
  useEffect(() => { if (!toast) return; const id = window.setTimeout(() => setToast(""), 2200); return () => window.clearTimeout(id); }, [toast]);
  const foundBanks = useMemo(() => banks.filter(b => `${b.name} ${b.type} ${b.last4}`.toLowerCase().includes(query.toLowerCase())), [banks, query]);
  const foundLogins = useMemo(() => logins.filter(x => `${x.name} ${x.url} ${x.user} ${x.group}`.toLowerCase().includes(query.toLowerCase())), [logins, query]);
  const copy = (text: string, label: string) => {
    if (!navigator.clipboard) { setToast("Clipboard unavailable in this preview"); return; }
    navigator.clipboard.writeText(text).then(() => setToast(`${label} copied - preview only`)).catch(() => setToast("Clipboard permission was blocked"));
  };
  const addItem = () => {
    if (!newName.trim()) return setToast("Give this item a name first");
    if (addType === "Bank account") setBanks(xs => [{ name: newName, type: "New account", last4: "0000", holder: newUser || "You", tone: "blue", mark: newName[0].toUpperCase(), favorite: false }, ...xs]);
    else setLogins(xs => [{ name: newName, url: "new-entry.local", user: newUser || "you@example.com", group: "Personal", initials: newName[0].toUpperCase(), tint: "violet", favorite: false }, ...xs]);
    setNewName(""); setNewUser(""); setAddOpen(false); setToast("Demo item added to this session");
  };
  const openAdd = (kind: "Bank account" | "Password" = "Password") => { setAddType(kind); setAddOpen(true); };
  const toggleLogin = (name: string) => setLogins(xs => xs.map(x => x.name === name ? { ...x, favorite: !x.favorite } : x));
  const go = (next: Section) => { setSection(next); setMobileNav(false); };

  let page: ReactNode;
  if (section === "Bank vault") page = <BankVault banks={foundBanks.filter(b => filter !== "Favorites" || b.favorite)} favorite={n => setBanks(xs => xs.map(b => b.name === n ? { ...b, favorite: !b.favorite } : b))} copy={copy} filter={filter} setFilter={setFilter} add={() => openAdd("Bank account")} />;
  else if (section === "Password vault") page = <PasswordVault items={foundLogins.filter(x => filter !== "Favorites" || x.favorite)} favorite={toggleLogin} copy={copy} filter={filter} setFilter={setFilter} add={() => openAdd("Password")} />;
  else if (section === "Custom vaults") page = <CustomVaults onAdd={() => setToast("Custom vault builder -- preview only")} />;
  else if (section === "Categories") page = <Categories />;
  else if (section === "Security center") page = <SecurityCenter />;
  else if (section === "Settings") page = <SettingsPage />;
  else page = <Dashboard banks={banks} logins={logins} copy={copy} go={go} add={() => openAdd()} />;

  return <main className={`shell ${light ? "light-mode" : ""}`}>
    <div className="ambient ambient-one" /><div className="ambient ambient-two" /><div className="ambient ambient-three" />
    <aside className={`sidebar ${mobileNav ? "mobile-open" : ""}`}>
      <div className="brand-row"><div className="brand-icon"><Shield size={18} strokeWidth={2.3} /></div><div className="brand-name">aurelia<span>vault</span></div><IButton label="Close menu" className="mobile-close" onClick={() => setMobileNav(false)}><X size={17} /></IButton></div>
      <button className="workspace-select"><div className="workspace-avatar">A</div><span><b>Alex Morgan</b><small>Personal workspace</small></span><ChevronDown size={15} /></button>
      <nav className="side-nav"><label className="nav-label">WORKSPACE</label>{primaryNav.map(({ label, icon: Icon }) => <button key={label} className={`nav-item ${section === label ? "active" : ""}`} onClick={() => go(label)}><Icon size={17} /><span>{label}</span>{label === "Bank vault" && <em className="nav-count">{banks.length}</em>}</button>)}<label className="nav-label manage-label">MANAGE</label>{manageNav.map(({ label, icon: Icon }) => <button key={label} className={`nav-item ${section === label ? "active" : ""}`} onClick={() => go(label)}><Icon size={17} /><span>{label}</span>{label === "Security center" && <span className="nav-dot" />}</button>)}</nav>
      <div className="sidebar-bottom"><div className="upgrade-card"><div className="upgrade-glow" /><div className="upgrade-icon"><Sparkles size={16} /></div><b>Your vault, elevated.</b><p>Private by design. Yours by default.</p><button onClick={() => setToast("Aurelia Personal preview")}>Explore Aurelia <ArrowRight size={13} /></button></div><button className="help-link" onClick={() => setToast("Help center -- coming soon")}><CircleHelp size={16} />Help center<ArrowUpRight size={13} /></button><div className="side-footer"><span className="online-dot" />Encrypted locally <span className="footer-version">v0.1 preview</span></div></div>
    </aside>

    <section className="main-area">
      <header className="topbar"><div className="breadcrumb"><IButton label="Open navigation" className="mobile-menu" onClick={() => setMobileNav(true)}><Menu size={19} /></IButton><span>Workspace</span><ChevronRight size={14} /><b>{section}</b></div><div className="top-actions"><button className={`search-trigger ${searchOpen ? "search-active" : ""}`} onClick={() => setSearchOpen(v => !v)}><Search size={15} /><span>{searchOpen ? <input autoFocus value={query} onChange={e => setQuery(e.target.value)} placeholder="Search your vault..." onClick={e => e.stopPropagation()} /> : "Search"}</span><kbd>Ctrl K</kbd></button><div className="top-divider" /><IButton label="Quick actions" onClick={() => setCommandOpen(true)}><Zap size={17} /></IButton><IButton label="Notifications" onClick={() => setToast("You're all caught up")}><Bell size={17} /><i className="notification-dot" /></IButton><IButton label={light ? "Switch to dark mode" : "Switch to light mode"} onClick={() => setLight(v => !v)}><Sparkles size={16} /></IButton><button className="avatar-button" onClick={() => go("Settings")}>AM</button></div></header>
      <div className="page-scroll"><div className="preview-banner"><span className="preview-pulse" />INTERACTIVE DESIGN PREVIEW <span className="banner-divider">--</span> SAMPLE DATA ONLY <button onClick={() => setToast("Never enter real banking or password details in this UI preview.")}>What's this?</button></div><AnimatePresence mode="wait"><motion.div key={section} className="page-content" initial={{ opacity: 0, y: 9 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: .24 }}>{page}</motion.div></AnimatePresence><footer className="page-footer"><span>(c) 2026 Aurelia Vault</span><span><span className="online-dot" />All systems calm</span><span>Crafted for your peace of mind <span className="footer-heart">*</span></span></footer></div>
    </section>
    <AnimatePresence>{commandOpen && <CommandPalette close={() => setCommandOpen(false)} select={s => { go(s as Section); setCommandOpen(false); }} add={() => { setCommandOpen(false); openAdd(); }} />}</AnimatePresence>
    <AnimatePresence>{addOpen && <AddModal type={addType} setType={setAddType} name={newName} setName={setNewName} user={newUser} setUser={setNewUser} close={() => setAddOpen(false)} save={addItem} />}</AnimatePresence>
    <AnimatePresence>{toast && <motion.div className="toast" initial={{ y: 18, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 12, opacity: 0 }}><Check size={15} />{toast}</motion.div>}</AnimatePresence>
  </main>;
}

function Dashboard({ banks, logins, copy, go, add }: { banks: Bank[]; logins: Login[]; copy: (v: string, l: string) => void; go: (s: Section) => void; add: () => void }) {
  return <><PageHeading eyebrow="TUESDAY, OCTOBER 14" title="Good morning, Alex" description="A little more clarity. A little more calm." action="Add new" onAction={add} />
    <div className="welcome-row"><div className="welcome-copy"><div className="welcome-kicker"><span className="live-spark" />YOUR PRIVATE SPACE</div><h2>Everything important,<br /><i>beautifully in its place.</i></h2><p>Your personal world is looking good today.</p><button className="welcome-link" onClick={() => go("Security center")}>View security overview <ArrowRight size={15} /></button></div><div className="welcome-orb"><div className="orb-core"><ShieldCheck size={33} strokeWidth={1.5} /></div><span className="orb-ring ring-a" /><span className="orb-ring ring-b" /><span className="orb-star star-a"><Sparkles size={13} /></span><span className="orb-star star-b"><Sparkles size={17} /></span><span className="orb-label">PRIVATE BY DESIGN</span></div><div className="welcome-foot"><span><span className="online-dot" />Your vault is looking healthy</span><span>Last secured <b>Just now</b></span></div></div>
    <div className="stats-grid"><Stat icon={<KeyRound size={17} />} label="Passwords" value={String(logins.length + 18)} sub="Across 4 categories" tone="blue" trend="+2 this month" onClick={() => go("Password vault")} /><Stat icon={<Landmark size={17} />} label="Bank accounts" value={String(banks.length)} sub="All accounts secured" tone="purple" trend="View accounts" onClick={() => go("Bank vault")} /><Stat icon={<FolderKanban size={17} />} label="Custom vaults" value="4" sub="Made for your life" tone="mint" trend="Explore vaults" onClick={() => go("Custom vaults")} /><Stat icon={<Star size={17} />} label="Favorites" value="6" sub="Your quick-access list" tone="amber" trend="See favorites" onClick={() => go("Categories")} /></div>
    <div className="section-head"><div><h3>Your bank vault</h3><p>Accounts, kept close and protected.</p></div><button className="text-link" onClick={() => go("Bank vault")}>View all <ArrowRight size={14} /></button></div><div className="bank-grid">{banks.slice(0, 2).map((b, i) => <BankCard key={b.name} bank={b} index={i} favorite={() => {}} copy={copy} compact />)}<button className="add-bank-card" onClick={() => go("Bank vault")}><span className="add-circle"><Plus size={18} /></span><b>Add an account</b><small>Keep your finances together</small></button></div>
    <div className="lower-grid"><div className="glass-panel recent-panel"><div className="panel-heading"><div><h3>Recently accessed</h3><p>Your recent activity, at a glance.</p></div><button className="more-button" aria-label="More activity"><MoreHorizontal size={19} /></button></div><div className="activity-list"><ActivityItem icon={<KeyRound size={16} />} tint="violet" title="Linear" detail="Password viewed" time="2m ago" /><ActivityItem icon={<Landmark size={16} />} tint="blue" title="Monarch Private" detail="Account details copied" time="Yesterday" /><ActivityItem icon={<CreditCard size={16} />} tint="green" title="Travel card" detail="Card added to favorites" time="Mon, 12" /></div><button className="panel-foot-link" onClick={() => go("Security center")}>View all activity <ArrowRight size={14} /></button></div><div className="glass-panel security-panel"><div className="panel-heading"><div><h3>Security health</h3><p>Looking good. Keep it that way.</p></div><button className="quiet-icon" onClick={() => go("Security center")}><ArrowUpRight size={16} /></button></div><div className="security-score"><Ring value={92} /><div><div className="score-badge"><span />EXCELLENT</div><strong>You're in control.</strong><p>Your vault is stronger than<br />most. A few refinements left.</p></div></div><div className="security-mini-stats"><div><span className="check-mini"><Check size={12} /></span><b>0</b><small>weak passwords</small></div><div><span className="check-mini"><Check size={12} /></span><b>0</b><small>reused passwords</small></div></div><button className="panel-foot-link" onClick={() => go("Security center")}>Open security center <ArrowRight size={14} /></button></div></div>
    <div className="section-head password-head"><div><h3>Quick access</h3><p>Your pinned logins, one tap away.</p></div><button className="text-link" onClick={() => go("Password vault")}>Password vault <ArrowRight size={14} /></button></div><div className="quick-grid">{logins.slice(0, 4).map(x => <div className="quick-item glass-panel" key={x.name}><Mark initials={x.initials} tint={x.tint} /><div className="quick-info"><b>{x.name}</b><small>{x.user}</small></div><IButton label={`Copy ${x.name} password`} onClick={() => copy("demo-password-sample", "Password")}><Copy size={15} /></IButton></div>)}</div>
  </>;
}

function Stat({ icon, label, value, sub, tone, trend, onClick }: { icon: ReactNode; label: string; value: string; sub: string; tone: string; trend: string; onClick: () => void }) {
  return <button className="stat-card glass-panel" onClick={onClick}><div className={`stat-icon ${tone}`}>{icon}</div><div className="stat-label">{label}<ArrowUpRight size={14} /></div><div className="stat-value">{value}</div><div className="stat-bottom"><span>{sub}</span><small>{trend}</small></div><span className={`stat-glow ${tone}`} /></button>;
}

function ActivityItem({ icon, tint, title, detail, time }: { icon: ReactNode; tint: string; title: string; detail: string; time: string }) {
  return <div className="activity-item"><div className={`activity-icon ${tint}`}>{icon}</div><div className="activity-copy"><b>{title}</b><small>{detail}</small></div><span>{time}</span></div>;
}

function BankCard({ bank, index, favorite, copy, compact = false }: { bank: Bank; index: number; favorite: () => void; copy: (v: string, l: string) => void; compact?: boolean }) {
  const [visible, setVisible] = useState(false);
  return <motion.article className={`bank-card bank-${bank.tone} ${compact ? "compact" : ""}`} initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: index * .07 }} whileHover={{ y: -4, transition: { duration: .2 } }}>
    <div className="bank-card-aura" /><div className="bank-card-top"><div className="bank-brand"><div className="bank-mark">{bank.mark}</div><div><b>{bank.name}</b><small>{bank.type}</small></div></div><div className="bank-card-actions"><IButton label={bank.favorite ? "Remove favorite" : "Add favorite"} onClick={favorite}><Star size={15} fill={bank.favorite ? "currentColor" : "none"} /></IButton><IButton label="More options" onClick={() => copy(bank.name, "Account name")}><MoreHorizontal size={17} /></IButton></div></div>
    <div className="bank-number-label">ACCOUNT NUMBER <button className="eye-button" onClick={() => setVisible(v => !v)} aria-label={visible ? "Hide account number" : "Show account number"}>{visible ? <EyeOff size={14} /> : <Eye size={14} />}</button></div><div className="bank-number"><span>{visible ? `0000 2471 00${bank.last4}` : `${"\u2022".repeat(4)} ${"\u2022".repeat(4)} ${"\u2022".repeat(4)} ${bank.last4}`}</span><IButton label="Copy account number" onClick={() => copy(`0000247100${bank.last4}`, "Account number")}><Copy size={14} /></IButton></div>
    <div className="bank-card-bottom"><div><small>ACCOUNT HOLDER</small><b>{bank.holder}</b></div><div className="bank-type-mark"><div className="bank-signal" /><div className="bank-signal second" /></div></div>
    {!compact && <><div className="bank-details"><div><span>IFSC CODE</span><b>MNPR0000421</b></div><div><span>ACCOUNT TYPE</span><b>{bank.type}</b></div><button onClick={() => copy("MNPR0000421", "IFSC code")}><Copy size={13} />Copy IFSC</button></div><div className="bank-credential-grid"><Credential label="CUSTOMER ID" value="**** 38A2" /><Credential label="NET BANKING" value="************" /><Credential label="TRANSACTION PIN" value="******" /><Credential label="MOBILE MPIN" value="****" /><Credential label="BRANCH" value="South Mumbai" /><Credential label="NOTES" value="Primary salary account" /></div></>}
  </motion.article>;
}
function Credential({ label, value }: { label: string; value: string }) {
  return <div className="bank-credential"><small>{label}</small><b>{value}</b></div>;
}

function BankVault({ banks, favorite, copy, filter, setFilter, add }: { banks: Bank[]; favorite: (n: string) => void; copy: (v: string, l: string) => void; filter: string; setFilter: (v: string) => void; add: () => void }) {
  return <><PageHeading eyebrow="YOUR FINANCES" title="Bank accounts" description="Your financial world, thoughtfully protected." action="Add account" onAction={add} /><div className="vault-toolbar"><div className="toolbar-summary"><span className="toolbar-dot" />{banks.length} accounts <span className="toolbar-sep">--</span>Updated just now</div><div className="toolbar-actions"><button className="filter-button" onClick={() => setFilter(filter === "All items" ? "Favorites" : "All items")}><SlidersHorizontal size={15} />{filter}<ChevronDown size={13} /></button><IButton label="More bank options"><MoreHorizontal size={18} /></IButton></div></div>{banks.length === 0 ? <EmptyState title="No accounts found" body="Try another search or add your first account." icon={<Landmark size={22} />} /> : <div className="bank-grid bank-vault-grid">{banks.map((b, i) => <BankCard key={b.name} bank={b} index={i} favorite={() => favorite(b.name)} copy={copy} />)}<button className="add-bank-card large" onClick={add}><span className="add-circle"><Plus size={18} /></span><b>Add an account</b><small>Keep your financial details together</small></button></div>}<div className="vault-tip"><div className="tip-icon"><ShieldCheck size={17} /></div><span><b>A quiet note on security</b><small>Sample details only. This preview does not store data securely.</small></span><ArrowRight size={15} /></div></>;
}

function PasswordVault({ items, favorite, copy, filter, setFilter, add }: { items: Login[]; favorite: (n: string) => void; copy: (v: string, l: string) => void; filter: string; setFilter: (v: string) => void; add: () => void }) {
  const generate = () => {
    const chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*_-+=?";
    const random = new Uint32Array(24); crypto.getRandomValues(random);
    copy(Array.from(random, n => chars[n % chars.length]).join(""), "Generated password");
  };
  return <><PageHeading eyebrow="YOUR DIGITAL LIFE" title="Password vault" description="Every login, right where it should be." action="Add password" onAction={add} /><div className="password-hero"><div className="password-hero-copy"><span className="hero-kicker"><Sparkles size={13} />YOUR DIGITAL KEYRING</span><h2>Simple, secure,<br /><i>seamlessly yours.</i></h2><p>Strong, unique passwords keep your world protected.</p><button className="button-glass" onClick={generate}><Zap size={15} />Generate a strong password</button></div><div className="key-orbit"><span className="key-orbit-ring one" /><span className="key-orbit-ring two" /><div className="key-glass"><KeyRound size={29} /></div><span className="orbit-point point-one" /><span className="orbit-point point-two" /><span className="orbit-word">UNIQUE BY DEFAULT</span></div><div className="password-hero-foot"><span><LockKeyhole size={13} />Password health <b>92%</b></span><span>Last audit <b>Today</b></span></div></div>
    <div className="vault-toolbar"><div className="toolbar-summary"><span className="toolbar-dot" />{items.length} saved logins <span className="toolbar-sep">--</span>4 categories</div><div className="toolbar-actions"><button className="filter-button" onClick={() => setFilter(filter === "All items" ? "Favorites" : "All items")}><SlidersHorizontal size={15} />{filter}<ChevronDown size={13} /></button><div className="view-toggle"><span className="selected-view">Grid</span><span>List</span></div></div></div>
    <div className="password-grid">{items.map((x, i) => <motion.article className="password-card glass-panel" key={x.name} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * .05 }} whileHover={{ y: -3 }}><div className="password-card-top"><Mark initials={x.initials} tint={x.tint} /><IButton label={x.favorite ? "Remove favorite" : "Add favorite"} onClick={() => favorite(x.name)}><Star size={15} fill={x.favorite ? "currentColor" : "none"} /></IButton></div><div className="password-name-row"><h3>{x.name}</h3><span className="verified-dot" /></div><div className="password-url"><Globe2 size={13} />{x.url}</div><div className="credential-line"><span>{x.user}</span><IButton label={`Copy ${x.name} username`} onClick={() => copy(x.user, "Username")}><Copy size={14} /></IButton></div><div className="password-preview"><span>{"\u2022".repeat(12)}</span><IButton label={`Copy ${x.name} password`} onClick={() => copy("demo-password-sample", "Password")}><Copy size={14} /></IButton></div><div className="password-card-foot"><span className={`category-pill ${x.tint}`}>{x.group}</span><span>Updated 3d ago</span></div></motion.article>)}<button className="add-password-card" onClick={add}><span className="add-circle"><Plus size={18} /></span><b>Add a login</b><small>Save your next credential</small></button></div>
  </>;
}

function CustomVaults({ onAdd }: { onAdd: () => void }) {
  const items = [{ name: "Travel documents", sub: "Passport -- 2 items", icon: <Globe2 size={19} />, tint: "blue", count: "02" }, { name: "My vehicles", sub: "Insurance -- 4 items", icon: <Wallet size={19} />, tint: "amber", count: "04" }, { name: "Digital assets", sub: "Wallet hints -- 3 items", icon: <KeyRound size={19} />, tint: "violet", count: "03" }, { name: "Home & property", sub: "Documents -- 5 items", icon: <Landmark size={19} />, tint: "mint", count: "05" }];
  return <><PageHeading eyebrow="MADE FOR YOUR LIFE" title="Custom vaults" description="A thoughtful place for everything else." action="Create a vault" onAction={onAdd} /><div className="custom-intro glass-panel"><div className="custom-orb"><FolderKanban size={23} /></div><div><h2>Make room for what matters.</h2><p>Build a private space around the details that make your life yours.</p></div><button className="button-primary" onClick={onAdd}><Plus size={15} />Create your first vault</button></div><div className="custom-vault-grid">{items.map((v, i) => <motion.button className="custom-vault-card glass-panel" key={v.name} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * .06 }} whileHover={{ y: -3 }} onClick={onAdd}><div className={`custom-vault-icon ${v.tint}`}>{v.icon}</div><span className="custom-vault-count">{v.count} ITEMS</span><h3>{v.name}</h3><p>{v.sub}</p><div className="custom-vault-bottom"><span>Private vault</span><ArrowUpRight size={15} /></div></motion.button>)}</div><div className="custom-builder glass-panel"><div className="builder-heading"><div className="builder-badge"><Sparkles size={14} />YOUR FIELDS, YOUR RULES</div><h3>Build a vault that fits <i>your life.</i></h3><p>Choose the fields you need. We'll shape the space around them.</p><button className="text-link" onClick={onAdd}>Explore the builder <ArrowRight size={14} /></button></div><div className="builder-preview"><div className="builder-window"><div className="window-dots"><i /><i /><i /></div><div className="builder-input"><span>Vehicle registration</span><span>MH 02 AB 1234</span></div><div className="builder-input"><span>Insurance renewal</span><span>18 Aug 2026</span></div><div className="builder-input"><span>Policy document</span><span className="file-chip"><Bookmark size={11} />policy.pdf</span></div></div><div className="builder-spark spark-one"><Sparkles size={15} /></div><div className="builder-spark spark-two"><Sparkles size={18} /></div></div></div></>;
}

function Categories() {
  const items = [{ name: "Work", count: 12, meta: "Passwords -- Logins", tint: "violet", icon: <FolderKanban size={18} /> }, { name: "Personal", count: 8, meta: "Cards -- Accounts", tint: "blue", icon: <Wallet size={18} /> }, { name: "Finance", count: 5, meta: "Banking -- Payments", tint: "mint", icon: <Banknote size={18} /> }, { name: "Travel", count: 4, meta: "Documents -- Rewards", tint: "amber", icon: <Globe2 size={18} /> }, { name: "Development", count: 7, meta: "Developer tools", tint: "rose", icon: <Command size={18} /> }, { name: "Design", count: 3, meta: "Creative tools", tint: "sand", icon: <Sparkles size={18} /> }];
  return <><PageHeading eyebrow="A PLACE FOR EVERYTHING" title="Categories" description="Your vault, organized around the way you think." action="New category" onAction={() => {}} /><div className="category-overview"><div><span className="category-overline">TOTAL COLLECTIONS</span><strong>06</strong><small>Thoughtfully organized</small></div><div className="category-breakdown"><div><span className="dot violet-dot" />Personal & work <b>20</b></div><div><span className="dot blue-dot" />Finance & travel <b>09</b></div><div><span className="dot mint-dot" />Everything else <b>10</b></div></div><div className="category-mini-chart">{[28,55,40,73,49,88,59,95,70,100,71,83,56,91,65,76,52,88,48,71].map((n,i) => <i key={i} style={{height: `${n}%`}} />)}</div></div><div className="category-grid">{items.map((x, i) => <motion.button className="category-card glass-panel" key={x.name} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * .04 }} whileHover={{ y: -3 }}><div className={`category-icon ${x.tint}`}>{x.icon}</div><span className="category-count">{String(x.count).padStart(2, "0")}</span><h3>{x.name}</h3><p>{x.meta}</p><div className="category-card-bottom"><span>Open collection</span><ArrowRight size={14} /></div></motion.button>)}</div></>;
}

function SecurityCenter() {
  return <><PageHeading eyebrow="YOUR PEACE OF MIND" title="Security center" description="A clearer picture of your digital wellbeing." action="Run a security check" onAction={() => {}} /><div className="security-hero glass-panel"><div className="security-hero-glow" /><div className="security-hero-copy"><span className="hero-kicker"><ShieldCheck size={14} />YOUR SECURITY PICTURE</span><h2>A strong foundation.<br /><i>Quiet confidence.</i></h2><p>Your vault is in great shape. Here's the full picture.</p><button className="button-glass"><Activity size={15} />Run security check</button></div><div className="hero-ring"><Ring value={92} size={150} /><span>OUT OF 100</span></div><div className="security-hero-bottom"><span><span className="online-dot" />Last check completed today</span><span>Next check <b>in 7 days</b></span></div></div><div className="security-metrics"><Metric icon={<Check size={17} />} value="0" label="Weak passwords" caption="Everything is strong" tint="mint" /><Metric icon={<Copy size={17} />} value="0" label="Reused passwords" caption="Every password is unique" tint="blue" /><Metric icon={<Fingerprint size={17} />} value="On" label="Biometric unlock" caption="Last used today" tint="violet" /><Metric icon={<Shield size={17} />} value="Today" label="Last backup" caption="Stored on this device" tint="amber" /></div><div className="lower-grid security-lower"><div className="glass-panel trend-panel"><div className="panel-heading"><div><h3>Vault health</h3><p>Your score over the last 30 days.</p></div><button className="filter-button">Last 30 days <ChevronDown size={13} /></button></div><div className="trend-chart"><div className="chart-y"><span>100</span><span>75</span><span>50</span><span>25</span></div><div className="chart-area"><div className="chart-gridlines"><i /><i /><i /><i /></div><svg viewBox="0 0 640 210" preserveAspectRatio="none"><defs><linearGradient id="areaFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="#8b7cff" stopOpacity=".28" /><stop offset="100%" stopColor="#8b7cff" stopOpacity="0" /></linearGradient></defs><path d="M0,140 C30,136 48,128 76,132 S123,115 150,122 S200,95 226,109 S278,97 302,92 S346,100 377,81 S425,75 452,78 S493,53 526,62 S571,45 598,50 S621,34 640,30 L640,210 L0,210 Z" fill="url(#areaFill)" /><path d="M0,140 C30,136 48,128 76,132 S123,115 150,122 S200,95 226,109 S278,97 302,92 S346,100 377,81 S425,75 452,78 S493,53 526,62 S571,45 598,50 S621,34 640,30" fill="none" stroke="#a395ff" strokeWidth="2.5" vectorEffect="non-scaling-stroke" /></svg><div className="chart-x"><span>Sep 15</span><span>Sep 22</span><span>Sep 29</span><span>Oct 06</span><span>Oct 14</span></div></div></div></div><div className="glass-panel recommendations"><div className="panel-heading"><div><h3>Small refinements</h3><p>Little things that make a difference.</p></div><span className="rec-count">2</span></div><Recommendation icon={<Fingerprint size={16} />} title="Enable fingerprint unlock" description="Make your vault even easier to open." /><Recommendation icon={<Shield size={16} />} title="Review recovery key" description="Make sure your recovery details are current." /></div></div></>;
}
function Metric({ icon, value, label, caption, tint }: { icon: ReactNode; value: string; label: string; caption: string; tint: string }) {
  return <div className="metric-card glass-panel"><div className={`metric-icon ${tint}`}>{icon}</div><strong>{value}</strong><b>{label}</b><small>{caption}</small></div>;
}
function Recommendation({ icon, title, description }: { icon: ReactNode; title: string; description: string }) {
  return <div className="recommendation"><div className="rec-icon purple">{icon}</div><div><b>{title}</b><small>{description}</small></div><button className="rec-action">Review <ArrowRight size={13} /></button></div>;
}
function SettingsPage() {
  return <><PageHeading eyebrow="YOUR SPACE, YOUR WAY" title="Settings" description="Make Aurelia feel a little more like yours." /><div className="settings-layout"><div className="settings-menu glass-panel"><button className="settings-menu-item active"><Settings2 size={16} />Preferences</button><button className="settings-menu-item"><ShieldCheck size={16} />Security & privacy</button><button className="settings-menu-item"><Bell size={16} />Notifications</button><button className="settings-menu-item"><Wallet size={16} />Your plan</button></div><div className="settings-content glass-panel"><div className="panel-heading"><div><h3>Preferences</h3><p>A few small details to make this space yours.</p></div></div><SettingRow icon={<Sparkles size={16} />} title="Appearance" detail="Choose your preferred atmosphere"><button className="setting-select">Midnight <ChevronDown size={13} /></button></SettingRow><SettingRow icon={<LockKeyhole size={16} />} title="Lock after inactivity" detail="Your vault closes when you step away"><button className="setting-select">1 minute <ChevronDown size={13} /></button></SettingRow><SettingRow icon={<Fingerprint size={16} />} title="Biometric unlock" detail="Use your fingerprint to unlock faster"><button className="toggle on"><i /></button></SettingRow><SettingRow icon={<Eye size={16} />} title="Hide sensitive details" detail="Keep account numbers hidden by default"><button className="toggle on"><i /></button></SettingRow><SettingRow icon={<Bell size={16} />} title="Security reminders" detail="A gentle nudge when something needs attention"><button className="toggle"><i /></button></SettingRow><div className="settings-footnote"><ShieldCheck size={15} />This preview uses sample data and has no account connection.</div></div></div></>;
}
function SettingRow({ icon, title, detail, children }: { icon: ReactNode; title: string; detail: string; children: ReactNode }) {
  return <div className="setting-row"><div className="setting-row-icon">{icon}</div><div className="setting-text"><b>{title}</b><small>{detail}</small></div>{children}</div>;
}
function EmptyState({ title, body, icon }: { title: string; body: string; icon: ReactNode }) {
  return <div className="empty-state glass-panel"><div className="empty-icon">{icon}</div><h3>{title}</h3><p>{body}</p></div>;
}

function CommandPalette({ close, select, add }: { close: () => void; select: (s: Section) => void; add: () => void }) {
  const actions: { label: string; hint: string; icon: ReactNode; section?: Section }[] = [
    { label: "Go to overview", hint: "Workspace", icon: <LayoutDashboard size={16} />, section: "Overview" },
    { label: "Open bank vault", hint: "Your finances", icon: <Landmark size={16} />, section: "Bank vault" },
    { label: "Open password vault", hint: "Your logins", icon: <KeyRound size={16} />, section: "Password vault" },
    { label: "Open security center", hint: "Your protection", icon: <ShieldCheck size={16} />, section: "Security center" },
    { label: "Add a new item", hint: "Quick action", icon: <Plus size={16} /> },
  ];
  const [query, setQuery] = useState("");
  return <motion.div className="command-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onMouseDown={e => { if (e.target === e.currentTarget) close(); }}><motion.div className="command-modal glass-panel" initial={{ opacity: 0, y: -12, scale: .98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -8, scale: .98 }} transition={{ duration: .18 }}><div className="command-search"><Search size={18} /><input autoFocus value={query} onChange={e => setQuery(e.target.value)} placeholder="What are you looking for?" /><kbd>ESC</kbd></div><div className="command-label">SUGGESTED</div>{actions.filter(x => x.label.toLowerCase().includes(query.toLowerCase())).map(x => <button className="command-result" key={x.label} onClick={() => x.section ? select(x.section) : add()}><span className="command-icon">{x.icon}</span><b>{x.label}</b><small>{x.hint}</small><ArrowRight size={14} /></button>)}<div className="command-hint"><span><kbd>Up</kbd><kbd>Down</kbd> to navigate</span><span><kbd>Enter</kbd> to select</span><span><kbd>Ctrl K</kbd> to close</span></div></motion.div></motion.div>;
}

function AddModal({ type, setType, name, setName, user, setUser, close, save }: { type: "Bank account" | "Password"; setType: (v: "Bank account" | "Password") => void; name: string; setName: (v: string) => void; user: string; setUser: (v: string) => void; close: () => void; save: () => void }) {
  return <motion.div className="command-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onMouseDown={e => { if (e.target === e.currentTarget) close(); }}><motion.div className="add-modal glass-panel" initial={{ opacity: 0, y: 10, scale: .98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 8, scale: .98 }}><button className="modal-close" aria-label="Close" onClick={close}><X size={17} /></button><div className="modal-icon"><Plus size={18} /></div><div className="eyebrow">QUICK ADD -- SAMPLE SESSION</div><h2>Add to your vault</h2><p className="modal-desc">Create a temporary demo item. Nothing is saved after you close this page.</p><div className="type-tabs"><button className={type === "Password" ? "selected" : ""} onClick={() => setType("Password")}><KeyRound size={15} />Password</button><button className={type === "Bank account" ? "selected" : ""} onClick={() => setType("Bank account")}><Landmark size={15} />Bank account</button></div><label className="form-label">{type === "Password" ? "WEBSITE OR APP NAME" : "BANK NAME"}<input autoFocus value={name} onChange={e => setName(e.target.value)} placeholder={type === "Password" ? "e.g. Studio Portal" : "e.g. Your local bank"} /></label><label className="form-label">{type === "Password" ? "USERNAME OR EMAIL" : "ACCOUNT HOLDER"}<input value={user} onChange={e => setUser(e.target.value)} placeholder={type === "Password" ? "you@example.com" : "Your name"} /></label><div className="preview-warning"><ShieldCheck size={15} /><span>Preview only. <b>Never enter real banking or password details.</b></span></div><Button className="button-primary modal-submit" onClick={save}>Add demo item <ArrowRight size={15} /></Button></motion.div></motion.div>;
}






