import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '../views/HomeView.vue'
import ListView from '../views/ListView.vue'
import NoteView from '../views/NoteView.vue'
import SettingsView from '../views/SettingsView.vue'
import TasksView from '../views/TasksView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView, meta: { keepAlive: true } },
    { path: '/note/:id?', component: NoteView },

    { path: '/lists', name: 'lists', component: ListView },
    { path: '/list/:id', name: 'list-detail', component: ListView },
    { path: '/tasks', name: 'tasks', component: TasksView },
    { path: '/task-list/:id', name: 'task-detail', component: ListView },

    { path: '/settings', component: SettingsView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
