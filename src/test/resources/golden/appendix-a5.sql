-- ============================================================
-- users
-- ============================================================
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.users FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_users
  ON public.users
  AS PERMISSIVE
  FOR ALL
  USING (tenant_id = current_setting('app.tenant_id'));

-- ============================================================
-- projects
-- ============================================================
ALTER TABLE public.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.projects FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_projects
  ON public.projects
  AS PERMISSIVE
  FOR ALL
  USING (tenant_id = current_setting('app.tenant_id'));

CREATE POLICY soft_delete_projects
  ON public.projects
  AS RESTRICTIVE
  FOR SELECT
  USING (is_deleted = false);

-- ============================================================
-- tasks
-- ============================================================
ALTER TABLE public.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tasks FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_via_project_tasks
  ON public.tasks
  AS PERMISSIVE
  FOR ALL
  USING (EXISTS (
    SELECT 1 FROM public.projects
    WHERE public.projects.id = public.tasks.project_id
      AND public.projects.tenant_id = current_setting('app.tenant_id')
  ));

-- ============================================================
-- comments
-- ============================================================
ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_comments
  ON public.comments
  AS PERMISSIVE
  FOR ALL
  USING (tenant_id = current_setting('app.tenant_id'));

-- ============================================================
-- files
-- ============================================================
ALTER TABLE public.files ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.files FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_via_project_files
  ON public.files
  AS PERMISSIVE
  FOR ALL
  USING (EXISTS (
    SELECT 1 FROM public.projects
    WHERE public.projects.id = public.files.project_id
      AND public.projects.tenant_id = current_setting('app.tenant_id')
  ));
