import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '../views/HomeView.vue'
import ListView from '../views/ListView.vue'
import NoteView from '../views/NoteView.vue'
import SettingsView from '../views/SettingsView.vue'
import TaskView from '../views/TaskView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/task/:preset', component: TaskView },
    { path: '/note/:id?', component: NoteView },

    { path: '/lists', name: 'lists', component: ListView },
    { path: '/list/:id', name: 'list-detail', component: ListView },

    { path: '/settings', component: SettingsView },
  ],
})

export default router
