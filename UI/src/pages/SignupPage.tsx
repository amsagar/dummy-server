import { FormEvent, useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { api, setAuthToken, setAuthUser } from "@/services/api";
import { toast } from "sonner";

export default function SignupPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const res = await api.auth.signup(email, password);
      if (res?.token) {
        setAuthToken(res.token);
        if (res?.user?.id && res?.user?.email) {
          setAuthUser({ id: res.user.id, email: res.user.email });
        }
        navigate("/chat", { replace: true });
        return;
      }
      toast.error("Signup failed");
    } catch (err: any) {
      toast.error(err?.message || "Signup failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-100 flex items-center justify-center p-6">
      <form onSubmit={onSubmit} className="w-full max-w-md bg-white rounded-xl border p-6 space-y-4">
        <h1 className="text-xl font-semibold text-slate-900">Create account</h1>
        <input className="w-full border rounded px-3 py-2" type="email" placeholder="Email" value={email} onChange={e => setEmail(e.target.value)} required />
        <input className="w-full border rounded px-3 py-2" type="password" placeholder="Password (min 8 chars)" value={password} onChange={e => setPassword(e.target.value)} required minLength={8} />
        <button className="w-full bg-blue-600 text-white rounded px-3 py-2 disabled:opacity-60" disabled={submitting}>
          {submitting ? "Creating..." : "Sign up"}
        </button>
        <p className="text-sm text-slate-600">
          Already have account? <Link className="text-blue-600" to="/login">Login</Link>
        </p>
      </form>
    </div>
  );
}
