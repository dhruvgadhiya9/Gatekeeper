import { Route, Routes } from "react-router";
import { ProtectedLayout } from "./auth/ProtectedLayout";
import { RequireRole } from "./auth/RequireRole";
import { HomePage } from "./pages/HomePage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ProjectDetailPage } from "./pages/projects/ProjectDetailPage";
import { ProjectListPage } from "./pages/projects/ProjectListPage";
import { ProjectEditPage, ProjectWizardPage } from "./pages/projects/wizard/ProjectWizardPage";
import { SettingsPage } from "./pages/SettingsPage";
import { UsersPage } from "./pages/UsersPage";

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedLayout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/projects" element={<ProjectListPage />} />
        <Route element={<RequireRole roles={["ADMIN", "QA_LEAD"]} />}>
          <Route path="/projects/new" element={<ProjectWizardPage />} />
          <Route path="/projects/:id/edit" element={<ProjectEditPage />} />
        </Route>
        <Route path="/projects/:id" element={<ProjectDetailPage />} />
        <Route element={<RequireRole roles={["ADMIN"]} />}>
          <Route path="/users" element={<UsersPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
