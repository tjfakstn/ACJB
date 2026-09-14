import type { ButtonHTMLAttributes } from 'react'

// Figma 공용 컴포넌트 'Button' (component set)의 variant를 그대로 가져옴.
// 색상은 Figma에서 뽑은 값이 Tailwind gray/red 기본 스케일과 거의 일치해서 커스텀 팔레트 없이 그대로 사용.
export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'chip' | 'chip-active'

const variantClasses: Record<ButtonVariant, string> = {
  primary: 'bg-gray-800 text-white hover:bg-gray-700',
  secondary: 'bg-white text-gray-800 border border-gray-800 hover:bg-gray-50',
  danger: 'bg-red-300 text-red-900 hover:bg-red-200',
  chip: 'rounded-full bg-gray-100 text-gray-800 border border-gray-200 hover:bg-gray-200',
  'chip-active': 'rounded-full bg-gray-800 text-white',
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
}

export function Button({ variant = 'primary', className = '', ...props }: ButtonProps) {
  const shape = variant === 'chip' || variant === 'chip-active' ? '' : 'rounded-md'
  return (
    <button
      className={`px-4 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${shape} ${variantClasses[variant]} ${className}`}
      {...props}
    />
  )
}
