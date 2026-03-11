import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '../views/HomeView.vue'
import TaskView from '../views/TaskView.vue'
import NoteView from '../views/NoteView.vue'
import ListView from '../views/ListView.vue'
import SettingsView from '../views/SettingsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/task/:preset', component: TaskView },
    { path: '/note', component: NoteView },
    // overview and detail share the same component; id is optional
    { path: '/list/:id?', component: ListView },
    { path: '/settings', component: SettingsView },
  ],
})

export default router