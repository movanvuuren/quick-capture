
import { describe, it, expect, vi } from 'vitest';

import { mount } from '@vue/test-utils';

import App from '../../src/App.vue';

import { createRouter, createWebHistory } from 'vue-router';



vi.mock('@capacitor/core', () => ({

  Capacitor: {

    isNativePlatform: vi.fn().mockReturnValue(false),

    isPluginAvailable: vi.fn().mockReturnValue(false),

  },

}));



vi.mock('@capacitor/app', () => ({

  App: {

    addListener: vi.fn(() => ({ remove: vi.fn() })),

  },

}));



vi.mock('@capacitor/local-notifications', () => ({

  LocalNotifications: {

    addListener: vi.fn(() => ({ remove: vi.fn() })),

  },

}));



const HomeComponent = {

  template: '<div>Home</div>',

};



const routes = [

  { path: '/', component: HomeComponent },

];



const router = createRouter({

  history: createWebHistory(),

  routes,

});



describe('App.vue', () => {

  it('renders the current route component', async () => {

    const wrapper = mount(App, {

      global: {

        plugins: [router],

      },

    });



    await router.isReady();



    expect(wrapper.findComponent(HomeComponent).exists()).toBe(true);

  });

});


