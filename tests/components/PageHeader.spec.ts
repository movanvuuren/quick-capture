import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PageHeader from '../../src/components/PageHeader.vue'

describe('PageHeader', () => {
  it('renders a home button and emits back when clicked', async () => {
    const wrapper = mount(PageHeader, {
      props: {
        title: 'Tasks',
      },
      global: {
        stubs: {
          House: true,
        },
      },
    })

    const button = wrapper.find('.back-button')
    expect(button.attributes('aria-label')).toBe('Go home')

    await button.trigger('click')

    expect(wrapper.emitted('back')).toHaveLength(1)
  })
})
